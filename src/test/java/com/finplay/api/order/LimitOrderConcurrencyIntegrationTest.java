// 지정가 체결의 잠금 순서(order→account→holding)가 실제 동시성 하에서 안전한지 증명하는 통합 테스트다.
// plan.md "동시성 테스트 시나리오" 4개를 그대로 구현한다(tasks.md 항목6): (a) 중복 체결 방지, (b) 지정가 SELL
// 체결과 시장가 SELL이 동시에 실행돼도 ABBA 데드락이 없음(이 spec의 핵심 증명 대상), (c) 매수 지정가 현금 예약·
// 부족 거부, (d) 매도 지정가 예약이 시장가·다른 지정가의 초과 매도를 막음. 단일 체결 end-to-end 배선은
// LimitOrderFillIntegrationTest(항목4)가 이미 검증하므로 여기서는 다루지 않는다(중복 방지).
// LMT-003(이슈 #218) 추가: plan.md "동시성 테스트 시나리오 추가"(tasks.md 항목10) — 취소(cancelOrder)와
// 체결(fillIfPending)이 같은 PENDING 주문에 동시에 경합할 때 정확히 한쪽만 성공하고 reservedCash/reservedQuantity가
// 이중 반환·이중 소비 없이 일관되는지 BUY·SELL 각 1개씩 검증한다.
package com.finplay.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.LimitOrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.service.LimitOrderCancelService;
import com.finplay.api.order.service.LimitOrderFillService;
import com.finplay.api.order.service.LimitOrderService;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LimitOrderConcurrencyIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 12, 0, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private LimitOrderService limitOrderService;

	@Autowired
	private LimitOrderFillService limitOrderFillService;

	@Autowired
	private LimitOrderCancelService limitOrderCancelService;

	@Autowired
	private OrderService orderService;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock clock;

	@BeforeEach
	void setUp() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
	}

	@AfterEach
	void tearDown() {
		redisTemplate.delete("feed:crypto:status");
	}

	// 시나리오 (a) 중복 체결 방지: 같은 PENDING 지정가 주문에 fillIfPending을 두 스레드에서 거의 동시에 호출해도
	// 정확히 1건의 Trade만 생성되고 예약 현금이 정확히 한 번만 실제 지출로 전환됨을 검증한다(하나는 락 대기 후
	// status != PENDING을 보고 no-op).
	@Test
	void concurrentFillEventsForSameOrderResultInExactlyOneTradeAndSingleCashConfirmation() throws Exception {
		User user = createUser("dup-fill");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("DUPFIL");

		BigDecimal quantity = new BigDecimal("0.1");
		BigDecimal limitPrice = new BigDecimal("10000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			user.getId(), "idem-dup-fill",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		Long orderId = created.orderId();

		Account reservedAccount = accountRepository.findById(account.getId()).orElseThrow();
		long reservedCashBefore = reservedAccount.getReservedCash();
		long cashBeforeFill = reservedAccount.getCashBalance();
		assertThat(reservedCashBefore).isGreaterThan(0L);

		runConcurrently(
			() -> limitOrderFillService.fillIfPending(orderId),
			() -> limitOrderFillService.fillIfPending(orderId));

		var filledOrder = orderRepository.findById(orderId).orElseThrow();
		assertThat(filledOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(countTradesForOrder(orderId)).isEqualTo(1L);

		Account accountAfterFill = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(accountAfterFill.getReservedCash()).isZero();
		// 예약분이 정확히 한 번만 실제 지출로 전환됐는지 — 두 번 전환됐다면 이 차액이 reservedCashBefore의 2배가 된다.
		assertThat(cashBeforeFill - accountAfterFill.getCashBalance()).isEqualTo(reservedCashBefore);
	}

	// 시나리오 (b, 핵심): 스레드 A는 지정가 SELL 체결(LimitOrderFillService, order→account→holding), 스레드 B는
	// 시장가 SELL(OrderExecutionService, 항목5 조정 후 account→holding)을 같은 계좌·같은 holding row에 대해
	// 동시에 실행한다. 둘 다 account를 먼저 잠그므로 ABBA 데드락 없이 타임아웃 없이 완료돼야 한다
	// (데드락이면 MySQL이 Deadlock found 예외를 던지거나 락 대기 타임아웃으로 실패한다 — runConcurrently가 그대로 전파).
	@Test
	void limitSellFillAndMarketSellExecuteConcurrentlyOnSameAccountAndHoldingWithoutDeadlock() throws Exception {
		User user = createUser("abba");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("ABBA");

		BigDecimal buyQuantity = new BigDecimal("20");
		BigDecimal price = new BigDecimal("100000");
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
		orderService.createOrder(user.getId(), "idem-abba-buy",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", buyQuantity));

		BigDecimal limitSellQuantity = new BigDecimal("8");
		LimitOrderResponse limitSell = limitOrderService.createLimitOrder(
			user.getId(), "idem-abba-limit-sell",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, limitSellQuantity, price));
		Long limitSellOrderId = limitSell.orderId();

		// availableQuantity = 20 - 8 = 12 → 시장가 10주는 예약과 겹치지 않고 함께 성공할 수 있는 조합이다.
		BigDecimal marketSellQuantity = new BigDecimal("10");

		runConcurrently(
			() -> limitOrderFillService.fillIfPending(limitSellOrderId),
			() -> orderService.createOrder(user.getId(), "idem-abba-market-sell",
				new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, "MARKET",
					marketSellQuantity)));

		var filledLimitOrder = orderRepository.findById(limitSellOrderId).orElseThrow();
		assertThat(filledLimitOrder.getStatus()).isEqualTo(OrderStatus.FILLED);

		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		assertThat(holding.getQuantity())
			.isEqualByComparingTo(buyQuantity.subtract(limitSellQuantity).subtract(marketSellQuantity));
		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	// 시나리오 (c): 매수 지정가 생성 성공 시 reserved_cash가 정확히 증가하고 cash_balance는 불변임을 DB로 검증한다.
	// 예약 가능 현금을 초과하는 두 번째 요청은 409 INSUFFICIENT_CASH로 거부되고 추가 예약이 없어야 한다.
	@Test
	void limitBuyReservesCashOnSuccessAndRejectsSecondRequestExceedingAvailableCashWithoutExtraReservation() {
		User user = createUser("cash-reserve");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("CASHRV");

		// amount = 0.5 * 12,000,000 = 6,000,000(> 계좌 기본 현금의 절반) — 동일 요청을 두 번 보내면 두 번째는
		// 반드시 availableCash를 초과하도록 고른 값이다.
		BigDecimal quantity = new BigDecimal("0.5");
		BigDecimal limitPrice = new BigDecimal("12000000");
		LimitOrderResponse first = limitOrderService.createLimitOrder(user.getId(), "idem-cash-1",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		assertThat(first.status()).isEqualTo("PENDING");

		Account afterFirst = accountRepository.findById(account.getId()).orElseThrow();
		long reservedCashAfterFirst = afterFirst.getReservedCash();
		assertThat(reservedCashAfterFirst).isGreaterThan(5_000_000L);
		assertThat(afterFirst.getCashBalance()).isEqualTo(10_000_000L);

		assertThatThrownBy(() -> limitOrderService.createLimitOrder(user.getId(), "idem-cash-2",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice)))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CASH));

		Account afterRejected = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(afterRejected.getReservedCash()).isEqualTo(reservedCashAfterFirst);
		assertThat(afterRejected.getCashBalance()).isEqualTo(10_000_000L);
	}

	// 시나리오 (d): 같은 holding에 대해 지정가 SELL을 예약한 뒤, 남은 availableQuantity를 초과하는 기존 시장가
	// SELL과 다른 지정가 SELL이 모두 409 INSUFFICIENT_QTY로 거부됨을 검증한다 — "기존 시장가 SELL도 예약
	// 원장을 반영한다" 완료조건의 핵심 증거다.
	@Test
	void limitSellReservationBlocksBothMarketSellAndAnotherLimitSellFromOverselling() {
		User user = createUser("qty-reserve");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("QTYRV");

		BigDecimal buyQuantity = new BigDecimal("10");
		BigDecimal price = new BigDecimal("100000");
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
		orderService.createOrder(user.getId(), "idem-qty-buy",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", buyQuantity));

		BigDecimal firstSellQuantity = new BigDecimal("7");
		LimitOrderResponse firstSell = limitOrderService.createLimitOrder(user.getId(), "idem-qty-limit-sell",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, firstSellQuantity, price));
		assertThat(firstSell.status()).isEqualTo("PENDING");

		Holding holdingAfterReservation = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		assertThat(holdingAfterReservation.getReservedQuantity()).isEqualByComparingTo("7");
		// availableQuantity = 10 - 7 = 3 → 5주 초과 매도는 시장가·지정가 모두 거부돼야 한다.
		BigDecimal oversellQuantity = new BigDecimal("5");

		assertThatThrownBy(() -> orderService.createOrder(user.getId(), "idem-qty-market-oversell",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, "MARKET", oversellQuantity)))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_QTY));

		assertThatThrownBy(() -> limitOrderService.createLimitOrder(user.getId(), "idem-qty-limit-oversell",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, oversellQuantity, price)))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_QTY));

		Holding holdingAfterRejections = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		assertThat(holdingAfterRejections.getQuantity()).isEqualByComparingTo("10");
		assertThat(holdingAfterRejections.getReservedQuantity()).isEqualByComparingTo("7");
	}

	// 시나리오 12 (BUY): 같은 PENDING 지정가 매수 주문에 cancelOrder와 fillIfPending을 동시에 호출한다. 둘 다
	// order → account 순으로 잠그므로 order row lock에서 직렬화되고, 정확히 한쪽만 성공해야 한다(plan.md
	// "동시성 테스트 시나리오 추가" 1번). 어느 쪽이 이길지는 스케줄링에 좌우되므로 두 결과 분기를 모두 검증한다.
	@Test
	void cancelAndFillRaceForPendingBuyOrderResultInExactlyOneWinnerWithConsistentCashLedger() throws Exception {
		User user = createUser("cancel-fill-buy");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("CXFBUY");

		BigDecimal quantity = new BigDecimal("0.1");
		BigDecimal limitPrice = new BigDecimal("10000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			user.getId(), "idem-cxf-buy",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		Long orderId = created.orderId();

		Account reservedAccount = accountRepository.findById(account.getId()).orElseThrow();
		long reservedCashBefore = reservedAccount.getReservedCash();
		long cashBefore = reservedAccount.getCashBalance();
		assertThat(reservedCashBefore).isGreaterThan(0L);

		// cancelOrder는 지는 쪽이면 ORDER_NOT_PENDING을 던지는 게 정상 동작이므로, 그 예외를 runConcurrently 밖으로
		// 전파시키지 않고 캡처해서 이후 분기 검증에 쓴다. fillIfPending은 지는 쪽이어도 예외 없이 no-op해야 하므로
		// 그대로 둔다 — 만약 여기서 예외가 나면 그 자체가 구현 버그이므로 테스트가 실패해야 맞다.
		AtomicReference<Exception> cancelException = new AtomicReference<>();
		runConcurrently(
			() -> {
				try {
					limitOrderCancelService.cancelOrder(user.getId(), orderId);
				} catch (Exception ex) {
					cancelException.set(ex);
				}
			},
			() -> limitOrderFillService.fillIfPending(orderId));

		Order finalOrder = orderRepository.findById(orderId).orElseThrow();
		Account accountAfter = accountRepository.findById(account.getId()).orElseThrow();

		if (finalOrder.getStatus() == OrderStatus.FILLED) {
			// 체결이 이겼다 — 취소는 ORDER_NOT_PENDING 예외로 실패해야 하고, 예약분은 정확히 한 번만 실제 지출로 전환된다.
			assertThat(cancelException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_PENDING));
			assertThat(countTradesForOrder(orderId)).isEqualTo(1L);
			assertThat(accountAfter.getReservedCash()).isZero();
			assertThat(cashBefore - accountAfter.getCashBalance()).isEqualTo(reservedCashBefore);
		} else {
			// 취소가 이겼다 — 체결은 예외 없이 조용히 no-op해야 하고, 예약분은 지출 없이 그대로 반환된다.
			assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
			assertThat(cancelException.get()).isNull();
			assertThat(countTradesForOrder(orderId)).isEqualTo(0L);
			assertThat(accountAfter.getReservedCash()).isZero();
			assertThat(accountAfter.getCashBalance()).isEqualTo(cashBefore);
		}
	}

	// 시나리오 12 (SELL): BUY와 대칭 패턴 — assert 대상만 계좌(reservedCash/cashBalance) 대신 holding
	// (reservedQuantity/quantity)으로 바꾼다(plan.md "동시성 테스트 시나리오 추가" 2번).
	@Test
	void cancelAndFillRaceForPendingSellOrderResultInExactlyOneWinnerWithConsistentHoldingLedger() throws Exception {
		User user = createUser("cancel-fill-sell");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("CXFSEL");

		BigDecimal buyQuantity = new BigDecimal("5");
		BigDecimal price = new BigDecimal("100000");
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
		orderService.createOrder(user.getId(), "idem-cxf-sell-buy",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", buyQuantity));

		BigDecimal sellQuantity = new BigDecimal("3");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			user.getId(), "idem-cxf-sell",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, sellQuantity, price));
		Long orderId = created.orderId();

		Holding reservedHolding = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		BigDecimal reservedQuantityBefore = reservedHolding.getReservedQuantity();
		BigDecimal quantityBefore = reservedHolding.getQuantity();
		assertThat(reservedQuantityBefore).isEqualByComparingTo(sellQuantity);

		AtomicReference<Exception> cancelException = new AtomicReference<>();
		runConcurrently(
			() -> {
				try {
					limitOrderCancelService.cancelOrder(user.getId(), orderId);
				} catch (Exception ex) {
					cancelException.set(ex);
				}
			},
			() -> limitOrderFillService.fillIfPending(orderId));

		Order finalOrder = orderRepository.findById(orderId).orElseThrow();
		Holding holdingAfter = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();

		if (finalOrder.getStatus() == OrderStatus.FILLED) {
			// 체결이 이겼다 — 취소는 ORDER_NOT_PENDING 예외로 실패해야 하고, 예약 수량은 실제 매도로 정확히 한 번만 소비된다.
			assertThat(cancelException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_PENDING));
			assertThat(countTradesForOrder(orderId)).isEqualTo(1L);
			assertThat(holdingAfter.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
			assertThat(quantityBefore.subtract(holdingAfter.getQuantity())).isEqualByComparingTo(sellQuantity);
		} else {
			// 취소가 이겼다 — 체결은 예외 없이 조용히 no-op해야 하고, 예약 수량은 매도 없이 그대로 반환된다.
			assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
			assertThat(cancelException.get()).isNull();
			assertThat(countTradesForOrder(orderId)).isEqualTo(0L);
			assertThat(holdingAfter.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
			assertThat(holdingAfter.getQuantity()).isEqualByComparingTo(quantityBefore);
		}
	}

	// PracticeIntentionConcurrencyIntegrationTest와 동일한 ready/start CountDownLatch 관례를 재사용한다 —
	// 두 액션을 준비 완료(ready) 후 동시에 출발(start)시켜 실제 락 경합을 재현하고, 어느 한쪽이라도 예외(데드락 등)를
	// 던지면 그대로 테스트 실패로 전파한다.
	private void runConcurrently(ThrowingRunnable actionA, ThrowingRunnable actionB) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Void> futureA = executor.submit(toCallable(actionA, ready, start));
			Future<Void> futureB = executor.submit(toCallable(actionB, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			futureA.get(15, TimeUnit.SECONDS);
			futureB.get(15, TimeUnit.SECONDS);
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private Callable<Void> toCallable(ThrowingRunnable action, CountDownLatch ready, CountDownLatch start) {
		return () -> {
			ready.countDown();
			start.await();
			action.run();
			return null;
		};
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private long countTradesForOrder(Long orderId) {
		Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trades WHERE order_id = ?", Long.class, orderId);
		return count == null ? 0L : count;
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), NOW));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, NOW));
	}

	private Instrument createCryptoInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, symbolPrefix + "코인", new BigDecimal("1000"), 5_000L, true, NOW));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
