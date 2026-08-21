// 지정가 체결의 잠금 순서(order→account→holding)가 실제 동시성 하에서 안전한지 증명하는 통합 테스트다.
// plan.md "동시성 테스트 시나리오" 4개를 그대로 구현한다(tasks.md 항목6): (a) 중복 체결 방지, (b) 지정가 SELL
// 체결과 시장가 SELL이 동시에 실행돼도 ABBA 데드락이 없음(이 spec의 핵심 증명 대상), (c) 매수 지정가 현금 예약·
// 부족 거부, (d) 매도 지정가 예약이 시장가·다른 지정가의 초과 매도를 막음. 단일 체결 end-to-end 배선은
// LimitOrderFillIntegrationTest(항목4)가 이미 검증하므로 여기서는 다루지 않는다(중복 방지).
// LMT-003(이슈 #218) 추가: plan.md "동시성 테스트 시나리오 추가"(tasks.md 항목10) — 취소(cancelOrder)와
// 체결(fillIfPending)이 같은 PENDING 주문에 동시에 경합할 때 정확히 한쪽만 성공하고 reservedCash/reservedQuantity가
// 이중 반환·이중 소비 없이 일관되는지 BUY·SELL 각 1개씩 검증한다.
// 시장가 매수 경로 락 보강(이슈 #224) 추가: plan.md "동시성 테스트 시나리오"(tasks.md 항목15, spec.md 시나리오
// 13~15) — (a) 시장가 매수 대 지정가 매수 생성의 계좌 경합, (b) 시장가 매수 대 지정가 매수 체결의 holdings
// lost-update 방지, (c) 조정된 시장가 매수와 기존 시장가 매도 간 ABBA 데드락 회귀.
// LMT-005(이슈 #239) 추가: plan.md "동시성 테스트 시나리오"(tasks.md 항목23, spec.md 시나리오 23·24) — (a, 이
// 기능의 핵심 증명) 예약 가능 현금·수량을 초과하는 PATCH 요청이 409로 거부된 후 주문·계좌·보유를 DB에서
// 재조회해 요청 전 값과 완전히 동일함을 확인(매수·매도 각 1개), (b) 수정-대-체결 동시 경합(체결·수정 두 경로
// 모두 예약 이중 반환·이중 소비 없음), (c) 수정-대-취소 동시 경합(동일).
// PR #240 리뷰 권장사항 3번 반영: 시나리오 24(수정-대-체결/수정-대-취소)는 BUY만 있었다 — holding 락까지
// 얽히는 SELL 변형을 각각 추가해 기존 LMT-003 시나리오 12(취소-대-체결)의 BUY/SELL 대칭 검증 전례를 따른다.
package com.finplay.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.LimitOrderUpdateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderCancelService;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import com.finplay.api.domain.order.service.LimitOrderModifyService;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
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
	private LimitOrderModifyService limitOrderModifyService;

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

		// cancelOrder는 지는 쪽이면 ORDER_ALREADY_FILLED를 던지는 게 정상 동작이므로, 그 예외를 runConcurrently 밖으로
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
			// 체결이 이겼다 — 취소는 ORDER_ALREADY_FILLED 예외로 실패해야 하고, 예약분은 정확히 한 번만 실제 지출로 전환된다.
			assertThat(cancelException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_ALREADY_FILLED));
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
			// 체결이 이겼다 — 취소는 ORDER_ALREADY_FILLED 예외로 실패해야 하고, 예약 수량은 실제 매도로 정확히 한 번만 소비된다.
			assertThat(cancelException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_ALREADY_FILLED));
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

	// 시나리오 13: 시장가 매수(현금 즉시 차감)와 지정가 매수 생성(현금 예약)이 같은 계좌에 거의 동시에 도착한다.
	// account 비관적 락으로 두 요청이 직렬화되므로, 두 요청을 합친 소비액이 원래 잔액을 초과하도록 설계해도
	// availableCash(=cashBalance-reservedCash)가 항상 0 이상으로 유지돼야 한다(plan.md "동시성 테스트 시나리오"
	// 1번, spec.md 시나리오 13). 어느 쪽이 이길지는 스케줄링에 좌우되므로 승자를 특정하지 않고 불변식만 검증한다.
	@Test
	void marketBuyAndLimitBuyCreationRaceOnSameAccountKeepAvailableCashNonNegative() throws Exception {
		User user = createUser("acct-race");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("ACCTRC");

		BigDecimal price = new BigDecimal("1000000");
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));

		// 시장가 매수: amount = 5 * 1,000,000 = 5,000,000, fee = 2,500 → cashRequired = 5,002,500
		BigDecimal marketBuyQuantity = new BigDecimal("5");
		// 지정가 매수 생성 예약: amount = 0.5 * 12,000,000 = 6,000,000, fee = 3,000 → cashRequired = 6,003,000
		// 두 요청을 합치면 11,005,500으로 계좌 초기 잔액(10,000,000)을 초과하도록 설계했다 — account 락이
		// 없거나 검증이 availableCash를 반영하지 않으면 둘 다 통과해 availableCash가 음수가 될 수 있다.
		BigDecimal limitQuantity = new BigDecimal("0.5");
		BigDecimal limitPrice = new BigDecimal("12000000");

		AtomicReference<Exception> marketBuyException = new AtomicReference<>();
		AtomicReference<Exception> limitCreateException = new AtomicReference<>();
		runConcurrently(
			() -> {
				try {
					orderService.createOrder(user.getId(), "idem-acctrc-market",
						new OrderCreateRequest(
							Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", marketBuyQuantity));
				} catch (Exception ex) {
					marketBuyException.set(ex);
				}
			},
			() -> {
				try {
					limitOrderService.createLimitOrder(user.getId(), "idem-acctrc-limit",
						new LimitOrderCreateRequest(
							Market.CRYPTO, instrument.getId(), OrderSide.BUY, limitQuantity, limitPrice));
				} catch (Exception ex) {
					limitCreateException.set(ex);
				}
			});

		Account accountAfter = accountRepository.findById(account.getId()).orElseThrow();
		long availableCashAfter = accountAfter.getCashBalance() - accountAfter.getReservedCash();

		boolean marketBuySucceeded = marketBuyException.get() == null;
		boolean limitCreateSucceeded = limitCreateException.get() == null;
		// 설계상 최소 한쪽은 개별적으로 감당 가능한 금액이므로 둘 다 거부되는 일은 없어야 한다.
		assertThat(marketBuySucceeded || limitCreateSucceeded).isTrue();

		// 핵심 불변식 — 합산 소비액이 잔액을 초과하도록 설계했으므로, account 락이 올바르게 요청을 직렬화하고
		// 검증이 매 요청 시점의 availableCash를 정확히 반영한다면 최소 한쪽은 거부되어 이 값이 음수가 될 수 없다.
		assertThat(availableCashAfter).isGreaterThanOrEqualTo(0L);
	}

	// 시나리오 14: 이미 보유 중인 종목에 대해 시장가 매수(HTTP 스레드)와 지정가 매수 체결(가격 피드 스레드)이
	// 거의 동시에 같은 holding row를 갱신한다. holdings 비관적 락(findByAccountIdAndInstrumentIdForUpdate)으로
	// 두 갱신이 직렬화되어 최종 수량에 두 매수가 모두 반영되고(lost update 없음), HoldingLot도 두 매수 각각의
	// 몫으로 2건 모두 생성돼야 한다(plan.md "동시성 테스트 시나리오" 2번, spec.md 시나리오 14).
	@Test
	void marketBuyAndLimitFillRaceOnSameHoldingBothApplyWithoutLostUpdate() throws Exception {
		User user = createUser("holding-race");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("HOLDRC");

		BigDecimal initialPrice = new BigDecimal("100000");
		priceStore.saveTick(instrument.getSymbol(), initialPrice, LocalDateTime.now(clock));
		BigDecimal initialQuantity = new BigDecimal("5");
		orderService.createOrder(user.getId(), "idem-holdrc-initial",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", initialQuantity));

		// 지정가 매수 주문을 미리 PENDING으로 만들어둔다. fillIfPending을 직접 호출하므로 현재가 도달 여부는
		// 무관하다(기존 concurrentFillEventsForSameOrder... 테스트와 동일 관례).
		BigDecimal limitPrice = new BigDecimal("150000");
		BigDecimal limitQuantity = new BigDecimal("2");
		LimitOrderResponse limitBuy = limitOrderService.createLimitOrder(user.getId(), "idem-holdrc-limit",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, limitQuantity, limitPrice));
		Long limitOrderId = limitBuy.orderId();

		// 시장가 매수가 쓸 현재가를 지정가보다 높게 갱신한다 — 리스너가 이 가격 갱신으로 지정가 주문을
		// 우연히 미리 체결시키지 않도록 하기 위함이다(BUY 체결 조건은 현재가 <= 지정가).
		BigDecimal marketPrice = new BigDecimal("200000");
		priceStore.saveTick(instrument.getSymbol(), marketPrice, LocalDateTime.now(clock));

		Holding holdingBefore = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		long lotCountBefore = countHoldingLots(holdingBefore.getId());

		BigDecimal marketBuyQuantity = new BigDecimal("3");
		runConcurrently(
			() -> orderService.createOrder(user.getId(), "idem-holdrc-market",
				new OrderCreateRequest(
					Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", marketBuyQuantity)),
			() -> limitOrderFillService.fillIfPending(limitOrderId));

		Holding holdingAfter = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		assertThat(holdingAfter.getQuantity())
			.isEqualByComparingTo(initialQuantity.add(marketBuyQuantity).add(limitQuantity));

		long lotCountAfter = countHoldingLots(holdingAfter.getId());
		assertThat(lotCountAfter - lotCountBefore).isEqualTo(2L);

		Order filledLimitOrder = orderRepository.findById(limitOrderId).orElseThrow();
		assertThat(filledLimitOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
	}

	// 시나리오 15: 조정된 시장가 매수(account→holding)와 기존 시장가 매도(이미 account→holding 순서로 잠그는
	// 흐름)가 서로 다른 주문으로 같은 계좌·holding을 동시에 대상으로 실행돼도, 반대 순서로 잠그는 흐름이 없으므로
	// 데드락 없이 완료돼야 한다(plan.md "동시성 테스트 시나리오" 3번, spec.md 시나리오 15). 데드락이면 MySQL이
	// Deadlock found 예외를 던지거나 락 대기 타임아웃으로 실패한다 — runConcurrently가 그대로 전파한다.
	@Test
	void adjustedMarketBuyAndExistingMarketSellExecuteConcurrentlyOnSameAccountAndHoldingWithoutDeadlock()
		throws Exception {
		User user = createUser("buy-sell-abba");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("BSABBA");

		BigDecimal price = new BigDecimal("100000");
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
		BigDecimal initialQuantity = new BigDecimal("10");
		orderService.createOrder(user.getId(), "idem-bsabba-initial",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", initialQuantity));

		BigDecimal marketBuyQuantity = new BigDecimal("2");
		BigDecimal marketSellQuantity = new BigDecimal("3");

		runConcurrently(
			() -> orderService.createOrder(user.getId(), "idem-bsabba-buy",
				new OrderCreateRequest(
					Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", marketBuyQuantity)),
			() -> orderService.createOrder(user.getId(), "idem-bsabba-sell",
				new OrderCreateRequest(
					Market.CRYPTO, instrument.getId(), OrderSide.SELL, "MARKET", marketSellQuantity)));

		Holding holdingAfter = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		assertThat(holdingAfter.getQuantity())
			.isEqualByComparingTo(initialQuantity.add(marketBuyQuantity).subtract(marketSellQuantity));
		assertThat(holdingAfter.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	// 시나리오 23 (BUY, 이 기능의 핵심 증명): 예약 가능 현금을 초과하는 PATCH 요청이 409 INSUFFICIENT_CASH로
	// 거부된 후 주문·계좌를 DB에서 재조회해 요청 전 값과 완전히 동일함을 확인한다 — 서비스 예외 타입만 보는
	// 얕은 검증이 아니라 실제 DB 값 대조다(spec.md LMT-005 완료 조건, plan.md "동시성 테스트 시나리오" 2번).
	// 취소 후 재생성 방식이었다면 사라졌을 주문이 원자적 처리(트랜잭션 롤백)로 그대로 유지됨을 증명한다.
	@Test
	void modifyRejectedByInsufficientCashLeavesOrderAndAccountUnchangedInDb() {
		User user = createUser("modify-cash-atomic");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("MODCSH");

		// amount = 0.1 * 10,000,000 = 1,000,000, fee = 500 → reservedCash = 1,000,500.
		BigDecimal quantity = new BigDecimal("0.1");
		BigDecimal limitPrice = new BigDecimal("10000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			user.getId(), "idem-modcsh-create",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		Long orderId = created.orderId();

		Order orderBefore = orderRepository.findById(orderId).orElseThrow();
		BigDecimal quantityBefore = orderBefore.getQuantity();
		BigDecimal limitPriceBefore = orderBefore.getLimitPrice();
		LocalDateTime requestedAtBefore = orderBefore.getRequestedAt();
		Account accountBefore = accountRepository.findById(account.getId()).orElseThrow();
		long reservedCashBefore = accountBefore.getReservedCash();
		long cashBalanceBefore = accountBefore.getCashBalance();
		assertThat(reservedCashBefore).isEqualTo(1_000_500L);

		// 지정가를 120,000,000으로 올리면 amount = 0.1 * 120,000,000 = 12,000,000, fee = 6,000 → total =
		// 12,006,000. 옛 예약을 해제해도 availableCash는 잔액 전체(10,000,000)로 돌아올 뿐이라 여전히 부족하다.
		assertThatThrownBy(() -> limitOrderModifyService.modifyOrder(
			user.getId(), orderId, new LimitOrderUpdateRequest(new BigDecimal("120000000"), null)))
			.isInstanceOf(BusinessException.class)
			.satisfies(
				ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CASH));

		Order orderAfter = orderRepository.findById(orderId).orElseThrow();
		assertThat(orderAfter.getQuantity()).isEqualByComparingTo(quantityBefore);
		assertThat(orderAfter.getLimitPrice()).isEqualByComparingTo(limitPriceBefore);
		assertThat(orderAfter.getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(orderAfter.getRequestedAt()).isEqualTo(requestedAtBefore);

		Account accountAfter = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(accountAfter.getReservedCash()).isEqualTo(reservedCashBefore);
		assertThat(accountAfter.getCashBalance()).isEqualTo(cashBalanceBefore);
	}

	// 시나리오 23 (SELL): 매도 버전 — 예약 가능 수량을 초과하는 PATCH 요청이 409 INSUFFICIENT_QTY로 거부된 후
	// 주문·holding을 DB에서 재조회해 요청 전 값과 완전히 동일함을 확인한다(위 BUY 테스트와 동일 근거).
	@Test
	void modifyRejectedByInsufficientQtyLeavesOrderAndHoldingUnchangedInDb() {
		User user = createUser("modify-qty-atomic");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("MODQTY");

		BigDecimal buyQuantity = new BigDecimal("10");
		BigDecimal price = new BigDecimal("100000");
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
		orderService.createOrder(user.getId(), "idem-modqty-buy",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", buyQuantity));

		BigDecimal sellQuantity = new BigDecimal("4");
		LimitOrderResponse created = limitOrderService.createLimitOrder(user.getId(), "idem-modqty-sell",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, sellQuantity, price));
		Long orderId = created.orderId();

		Order orderBefore = orderRepository.findById(orderId).orElseThrow();
		BigDecimal quantityBefore = orderBefore.getQuantity();
		BigDecimal limitPriceBefore = orderBefore.getLimitPrice();
		LocalDateTime requestedAtBefore = orderBefore.getRequestedAt();
		Holding holdingBefore = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		BigDecimal totalQuantityBefore = holdingBefore.getQuantity();
		BigDecimal reservedQuantityBefore = holdingBefore.getReservedQuantity();
		assertThat(reservedQuantityBefore).isEqualByComparingTo(sellQuantity);

		// 보유수량(10)을 초과하는 15로 올리면 옛 예약(4)을 해제해도 availableQuantity는 보유수량 전체(10)로
		// 돌아올 뿐이라 여전히 부족하다.
		assertThatThrownBy(() -> limitOrderModifyService.modifyOrder(
			user.getId(), orderId, new LimitOrderUpdateRequest(null, new BigDecimal("15"))))
			.isInstanceOf(BusinessException.class)
			.satisfies(
				ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_QTY));

		Order orderAfter = orderRepository.findById(orderId).orElseThrow();
		assertThat(orderAfter.getQuantity()).isEqualByComparingTo(quantityBefore);
		assertThat(orderAfter.getLimitPrice()).isEqualByComparingTo(limitPriceBefore);
		assertThat(orderAfter.getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(orderAfter.getRequestedAt()).isEqualTo(requestedAtBefore);

		Holding holdingAfter = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		assertThat(holdingAfter.getQuantity()).isEqualByComparingTo(totalQuantityBefore);
		assertThat(holdingAfter.getReservedQuantity()).isEqualByComparingTo(reservedQuantityBefore);
	}

	// 시나리오 24 전반(수정-대-체결): PENDING 지정가 매수 주문에 스레드 A는 modifyOrder, 스레드 B는
	// fillIfPending을 동시 호출한다. 둘 다 order를 가장 먼저 잠그므로 order row lock에서 직렬화된다 —
	// 체결이 이기면 수정은 ORDER_ALREADY_FILLED로 거부되고 체결은 원래 값 기준으로 확정되며, 수정이 이기면
	// 수정이 재예약을 마친 뒤 체결 트리거가 변경된 값을 기준으로 판정해 확정한다. 두 경로 모두 예약 이중
	// 반환·이중 소비가 없어야 한다(plan.md "동시성 테스트 시나리오" 3번, spec.md 시나리오 24).
	@Test
	void modifyAndFillRaceForPendingBuyOrderApplyReservationExactlyOnceRegardlessOfWinner() throws Exception {
		User user = createUser("modify-fill-race");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("MODFIL");

		// 원래 예약: amount = 0.1 * 10,000,000 = 1,000,000, fee = 500 → total = 1,000,500.
		BigDecimal quantity = new BigDecimal("0.1");
		BigDecimal limitPrice = new BigDecimal("10000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			user.getId(), "idem-modfil-create",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		Long orderId = created.orderId();

		Account accountBefore = accountRepository.findById(account.getId()).orElseThrow();
		long cashBefore = accountBefore.getCashBalance();
		assertThat(accountBefore.getReservedCash()).isEqualTo(1_000_500L);

		// 수정 후 예약: amount = 0.1 * 20,000,000 = 2,000,000, fee = 1,000 → total = 2,001,000(현금 충분).
		BigDecimal newLimitPrice = new BigDecimal("20000000");

		AtomicReference<Exception> modifyException = new AtomicReference<>();
		AtomicReference<LimitOrderResponse> modifyResult = new AtomicReference<>();
		runConcurrently(
			() -> {
				try {
					modifyResult.set(limitOrderModifyService.modifyOrder(
						user.getId(), orderId, new LimitOrderUpdateRequest(newLimitPrice, null)));
				} catch (Exception ex) {
					modifyException.set(ex);
				}
			},
			() -> limitOrderFillService.fillIfPending(orderId));

		Order finalOrder = orderRepository.findById(orderId).orElseThrow();
		Account accountAfter = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(countTradesForOrder(orderId)).isEqualTo(1L);
		assertThat(accountAfter.getReservedCash()).isZero();

		if (modifyException.get() != null) {
			// 체결이 이겼다 — 수정은 ORDER_ALREADY_FILLED로 거부되고, 체결은 원래 값(limitPrice=10,000,000)
			// 기준으로 확정돼 예약분이 정확히 한 번만 실제 지출로 전환된다.
			assertThat(modifyException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_ALREADY_FILLED));
			assertThat(finalOrder.getLimitPrice()).isEqualByComparingTo(limitPrice);
			assertThat(cashBefore - accountAfter.getCashBalance()).isEqualTo(1_000_500L);
		} else {
			// 수정이 이겼다 — 수정이 재예약(1,000,500 해제 → 2,001,000 재예약)을 마친 뒤 체결이 변경된 값
			// (limitPrice=20,000,000) 기준으로 확정돼 예약분이 정확히 한 번만 실제 지출로 전환된다.
			assertThat(modifyResult.get()).isNotNull();
			assertThat(finalOrder.getLimitPrice()).isEqualByComparingTo(newLimitPrice);
			assertThat(cashBefore - accountAfter.getCashBalance()).isEqualTo(2_001_000L);
		}
	}

	// 시나리오 24 후반(수정-대-취소): 같은 패턴으로 스레드 A는 modifyOrder, 스레드 B는 cancelOrder를 동시
	// 호출한다. 취소가 이기면 수정은 ORDER_ALREADY_CANCELLED로 거부되고 취소는 원래 예약을 반환하며, 수정이
	// 이기면 수정이 재예약을 마친 뒤 취소가 변경된 값 기준으로 재예약분을 반환한다. 두 경로 모두 예약이 이중
	// 반환되지 않아야 한다(plan.md "동시성 테스트 시나리오" 4번, spec.md 시나리오 24 후반).
	@Test
	void modifyAndCancelRaceForPendingBuyOrderReleaseReservationExactlyOnceRegardlessOfWinner() throws Exception {
		User user = createUser("modify-cancel-race");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("MODCXL");

		// 원래 예약: amount = 0.1 * 10,000,000 = 1,000,000, fee = 500 → total = 1,000,500.
		BigDecimal quantity = new BigDecimal("0.1");
		BigDecimal limitPrice = new BigDecimal("10000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			user.getId(), "idem-modcxl-create",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		Long orderId = created.orderId();

		Account accountBefore = accountRepository.findById(account.getId()).orElseThrow();
		long cashBefore = accountBefore.getCashBalance();

		// 수정 후 예약: amount = 0.1 * 20,000,000 = 2,000,000, fee = 1,000 → total = 2,001,000(현금 충분).
		BigDecimal newLimitPrice = new BigDecimal("20000000");

		AtomicReference<Exception> modifyException = new AtomicReference<>();
		AtomicReference<Exception> cancelException = new AtomicReference<>();
		AtomicReference<LimitOrderResponse> modifyResult = new AtomicReference<>();
		runConcurrently(
			() -> {
				try {
					modifyResult.set(limitOrderModifyService.modifyOrder(
						user.getId(), orderId, new LimitOrderUpdateRequest(newLimitPrice, null)));
				} catch (Exception ex) {
					modifyException.set(ex);
				}
			},
			() -> {
				try {
					limitOrderCancelService.cancelOrder(user.getId(), orderId);
				} catch (Exception ex) {
					cancelException.set(ex);
				}
			});

		Order finalOrder = orderRepository.findById(orderId).orElseThrow();
		Account accountAfter = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(accountAfter.getReservedCash()).isZero();
		// 어느 쪽이 이겨도 체결이 없었으므로 cashBalance는 항상 시작값과 같다(이중 반환·이중 소비 모두 없음).
		assertThat(accountAfter.getCashBalance()).isEqualTo(cashBefore);
		// cancelOrder는 지는 쪽이어도 예외 없이 성공해야 한다(취소는 order가 PENDING이기만 하면 항상 성공 —
		// 수정이 이겼더라도 상태는 여전히 PENDING이므로 뒤이은 취소는 변경된 값 기준으로 정상 처리된다).
		assertThat(cancelException.get()).isNull();

		if (modifyException.get() != null) {
			// 취소가 이겼다 — 수정은 ORDER_ALREADY_CANCELLED로 거부되고, 취소는 원래 예약(1,000,500)을
			// 그대로 반환한다.
			assertThat(modifyException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_ALREADY_CANCELLED));
			assertThat(finalOrder.getLimitPrice()).isEqualByComparingTo(limitPrice);
		} else {
			// 수정이 이겼다 — 수정이 재예약(1,000,500 해제 → 2,001,000 재예약)을 마친 뒤, 취소가 변경된 값
			// (limitPrice=20,000,000) 기준으로 재예약분을 그대로 반환한다.
			assertThat(modifyResult.get()).isNotNull();
			assertThat(finalOrder.getLimitPrice()).isEqualByComparingTo(newLimitPrice);
		}
	}

	// 시나리오 24 (SELL, 수정-대-체결): BUY 버전과 대칭 패턴 — holding을 거쳐 잠그는 SELL 경로를 검증한다(PR #240
	// 리뷰 권장사항 3번). 원래 예약(3)을 수량 5로 올리는 수정과 체결이 경합한다. 둘 다 order를 먼저 잠그므로
	// 순서가 정해지면 나중 트랜잭션은 갱신된 상태를 본다 — 체결이 먼저 커밋되면 원래 수량(3)으로 확정되고 뒤이은
	// 수정은 ORDER_ALREADY_FILLED로 거부되며, 수정이 먼저 커밋되면 재예약(3 해제→5 재예약)을 마친 뒤 체결이 변경된
	// 수량(5) 기준으로 확정된다. 두 경로 모두 holding.reservedQuantity가 이중 반환·이중 소비 없이 0으로 수렴해야
	// 한다.
	@Test
	void modifyAndFillRaceForPendingSellOrderApplyReservationExactlyOnceRegardlessOfWinner() throws Exception {
		User user = createUser("modify-fill-race-sell");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("MFILSL");

		BigDecimal buyQuantity = new BigDecimal("10");
		BigDecimal price = new BigDecimal("100000");
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
		orderService.createOrder(user.getId(), "idem-mfilsl-buy",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", buyQuantity));

		BigDecimal sellQuantity = new BigDecimal("3");
		LimitOrderResponse created = limitOrderService.createLimitOrder(user.getId(), "idem-mfilsl-sell",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, sellQuantity, price));
		Long orderId = created.orderId();

		Holding holdingBefore = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		BigDecimal quantityBefore = holdingBefore.getQuantity();
		assertThat(holdingBefore.getReservedQuantity()).isEqualByComparingTo(sellQuantity);

		// 수정 후 예약 수량: 3 → 5(보유수량 10 대비 여전히 감당 가능).
		BigDecimal newQuantity = new BigDecimal("5");

		AtomicReference<Exception> modifyException = new AtomicReference<>();
		AtomicReference<LimitOrderResponse> modifyResult = new AtomicReference<>();
		runConcurrently(
			() -> {
				try {
					modifyResult.set(limitOrderModifyService.modifyOrder(
						user.getId(), orderId, new LimitOrderUpdateRequest(null, newQuantity)));
				} catch (Exception ex) {
					modifyException.set(ex);
				}
			},
			() -> limitOrderFillService.fillIfPending(orderId));

		Order finalOrder = orderRepository.findById(orderId).orElseThrow();
		Holding holdingAfter = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(countTradesForOrder(orderId)).isEqualTo(1L);
		assertThat(holdingAfter.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);

		if (modifyException.get() != null) {
			// 체결이 이겼다 — 수정은 ORDER_ALREADY_FILLED로 거부되고, 체결은 원래 수량(3) 기준으로 확정돼
			// 예약분이 정확히 한 번만 실제 매도로 소비된다.
			assertThat(modifyException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_ALREADY_FILLED));
			assertThat(finalOrder.getQuantity()).isEqualByComparingTo(sellQuantity);
			assertThat(quantityBefore.subtract(holdingAfter.getQuantity())).isEqualByComparingTo(sellQuantity);
		} else {
			// 수정이 이겼다 — 수정이 재예약(3 해제 → 5 재예약)을 마친 뒤 체결이 변경된 수량(5) 기준으로
			// 확정돼 예약분이 정확히 한 번만 실제 매도로 소비된다.
			assertThat(modifyResult.get()).isNotNull();
			assertThat(finalOrder.getQuantity()).isEqualByComparingTo(newQuantity);
			assertThat(quantityBefore.subtract(holdingAfter.getQuantity())).isEqualByComparingTo(newQuantity);
		}
	}

	// 시나리오 24 (SELL, 수정-대-취소): 같은 패턴으로 스레드 A는 modifyOrder, 스레드 B는 cancelOrder를 동시
	// 호출한다. 취소가 먼저 커밋되면 원래 예약(3)을 그대로 반환하고 뒤이은 수정은 ORDER_ALREADY_CANCELLED로
	// 거부되며, 수정이 먼저 커밋되면 재예약(3 해제 → 5 재예약)을 마친 뒤 취소가 변경된 수량(5) 기준으로
	// 재예약분을 반환한다. 두 경로 모두 holding.reservedQuantity가 이중 반환 없이 0으로 수렴하고, 실제 매도가
	// 없었으므로 holding.quantity는 항상 시작값 그대로여야 한다.
	@Test
	void modifyAndCancelRaceForPendingSellOrderReleaseReservationExactlyOnceRegardlessOfWinner() throws Exception {
		User user = createUser("modify-cancel-race-sell");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("MCXLSL");

		BigDecimal buyQuantity = new BigDecimal("10");
		BigDecimal price = new BigDecimal("100000");
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));
		orderService.createOrder(user.getId(), "idem-mcxlsl-buy",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", buyQuantity));

		BigDecimal sellQuantity = new BigDecimal("3");
		LimitOrderResponse created = limitOrderService.createLimitOrder(user.getId(), "idem-mcxlsl-sell",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, sellQuantity, price));
		Long orderId = created.orderId();

		Holding holdingBefore = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		BigDecimal quantityBefore = holdingBefore.getQuantity();

		// 수정 후 예약 수량: 3 → 5(보유수량 10 대비 여전히 감당 가능).
		BigDecimal newQuantity = new BigDecimal("5");

		AtomicReference<Exception> modifyException = new AtomicReference<>();
		AtomicReference<Exception> cancelException = new AtomicReference<>();
		AtomicReference<LimitOrderResponse> modifyResult = new AtomicReference<>();
		runConcurrently(
			() -> {
				try {
					modifyResult.set(limitOrderModifyService.modifyOrder(
						user.getId(), orderId, new LimitOrderUpdateRequest(null, newQuantity)));
				} catch (Exception ex) {
					modifyException.set(ex);
				}
			},
			() -> {
				try {
					limitOrderCancelService.cancelOrder(user.getId(), orderId);
				} catch (Exception ex) {
					cancelException.set(ex);
				}
			});

		Order finalOrder = orderRepository.findById(orderId).orElseThrow();
		Holding holdingAfter = holdingRepository
			.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();
		assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(holdingAfter.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		// 어느 쪽이 이겨도 실제 매도가 없었으므로 quantity는 항상 시작값과 같다(이중 반환·이중 소비 모두 없음).
		assertThat(holdingAfter.getQuantity()).isEqualByComparingTo(quantityBefore);
		// cancelOrder는 지는 쪽이어도 예외 없이 성공해야 한다(취소는 order가 PENDING이기만 하면 항상 성공 —
		// 수정이 이겼더라도 상태는 여전히 PENDING이므로 뒤이은 취소는 변경된 값 기준으로 정상 처리된다).
		assertThat(cancelException.get()).isNull();

		if (modifyException.get() != null) {
			// 취소가 이겼다 — 수정은 ORDER_ALREADY_CANCELLED로 거부되고, 취소는 원래 예약(3)을 그대로
			// 반환한다.
			assertThat(modifyException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_ALREADY_CANCELLED));
			assertThat(finalOrder.getQuantity()).isEqualByComparingTo(sellQuantity);
		} else {
			// 수정이 이겼다 — 수정이 재예약(3 해제 → 5 재예약)을 마친 뒤, 취소가 변경된 수량(5) 기준으로
			// 재예약분을 그대로 반환한다.
			assertThat(modifyResult.get()).isNotNull();
			assertThat(finalOrder.getQuantity()).isEqualByComparingTo(newQuantity);
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

	private long countHoldingLots(Long holdingId) {
		Long count = jdbcTemplate
			.queryForObject("SELECT COUNT(*) FROM holding_lots WHERE holding_id = ?", Long.class, holdingId);
		return count == null ? 0L : count;
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), NOW));
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, NOW));
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
