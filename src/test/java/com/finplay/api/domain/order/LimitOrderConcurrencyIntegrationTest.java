package com.finplay.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.LimitOrderUpdateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderCancelService;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import com.finplay.api.domain.order.service.LimitOrderModifyService;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
		assertThat(cashBeforeFill - accountAfterFill.getCashBalance()).isEqualTo(reservedCashBefore);
	}

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

	@Test
	void limitBuyReservesCashOnSuccessAndRejectsSecondRequestExceedingAvailableCashWithoutExtraReservation() {
		User user = createUser("cash-reserve");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("CASHRV");

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
			assertThat(cancelException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_ALREADY_FILLED));
			assertThat(countTradesForOrder(orderId)).isEqualTo(1L);
			assertThat(accountAfter.getReservedCash()).isZero();
			assertThat(cashBefore - accountAfter.getCashBalance()).isEqualTo(reservedCashBefore);
		} else {
			assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
			assertThat(cancelException.get()).isNull();
			assertThat(countTradesForOrder(orderId)).isEqualTo(0L);
			assertThat(accountAfter.getReservedCash()).isZero();
			assertThat(accountAfter.getCashBalance()).isEqualTo(cashBefore);
		}
	}

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
			assertThat(cancelException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_ALREADY_FILLED));
			assertThat(countTradesForOrder(orderId)).isEqualTo(1L);
			assertThat(holdingAfter.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
			assertThat(quantityBefore.subtract(holdingAfter.getQuantity())).isEqualByComparingTo(sellQuantity);
		} else {
			assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
			assertThat(cancelException.get()).isNull();
			assertThat(countTradesForOrder(orderId)).isEqualTo(0L);
			assertThat(holdingAfter.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
			assertThat(holdingAfter.getQuantity()).isEqualByComparingTo(quantityBefore);
		}
	}

	@Test
	void marketBuyAndLimitBuyCreationRaceOnSameAccountKeepAvailableCashNonNegative() throws Exception {
		User user = createUser("acct-race");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("ACCTRC");

		BigDecimal price = new BigDecimal("1000000");
		priceStore.saveTick(instrument.getSymbol(), price, LocalDateTime.now(clock));

		BigDecimal marketBuyQuantity = new BigDecimal("5");
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
		assertThat(marketBuySucceeded || limitCreateSucceeded).isTrue();

		assertThat(availableCashAfter).isGreaterThanOrEqualTo(0L);
	}

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

		BigDecimal limitPrice = new BigDecimal("150000");
		BigDecimal limitQuantity = new BigDecimal("2");
		LimitOrderResponse limitBuy = limitOrderService.createLimitOrder(user.getId(), "idem-holdrc-limit",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, limitQuantity, limitPrice));
		Long limitOrderId = limitBuy.orderId();

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

	@Test
	void modifyRejectedByInsufficientCashLeavesOrderAndAccountUnchangedInDb() {
		User user = createUser("modify-cash-atomic");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("MODCSH");

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

	@Test
	void modifyAndFillRaceForPendingBuyOrderApplyReservationExactlyOnceRegardlessOfWinner() throws Exception {
		User user = createUser("modify-fill-race");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("MODFIL");

		BigDecimal quantity = new BigDecimal("0.1");
		BigDecimal limitPrice = new BigDecimal("10000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			user.getId(), "idem-modfil-create",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		Long orderId = created.orderId();

		Account accountBefore = accountRepository.findById(account.getId()).orElseThrow();
		long cashBefore = accountBefore.getCashBalance();
		assertThat(accountBefore.getReservedCash()).isEqualTo(1_000_500L);

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
			assertThat(modifyException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_ALREADY_FILLED));
			assertThat(finalOrder.getLimitPrice()).isEqualByComparingTo(limitPrice);
			assertThat(cashBefore - accountAfter.getCashBalance()).isEqualTo(1_000_500L);
		} else {
			assertThat(modifyResult.get()).isNotNull();
			assertThat(finalOrder.getLimitPrice()).isEqualByComparingTo(newLimitPrice);
			assertThat(cashBefore - accountAfter.getCashBalance()).isEqualTo(2_001_000L);
		}
	}

	@Test
	void modifyAndCancelRaceForPendingBuyOrderReleaseReservationExactlyOnceRegardlessOfWinner() throws Exception {
		User user = createUser("modify-cancel-race");
		Account account = createAccount(user);
		Instrument instrument = createCryptoInstrument("MODCXL");

		BigDecimal quantity = new BigDecimal("0.1");
		BigDecimal limitPrice = new BigDecimal("10000000");
		LimitOrderResponse created = limitOrderService.createLimitOrder(
			user.getId(), "idem-modcxl-create",
			new LimitOrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, quantity, limitPrice));
		Long orderId = created.orderId();

		Account accountBefore = accountRepository.findById(account.getId()).orElseThrow();
		long cashBefore = accountBefore.getCashBalance();

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
		assertThat(accountAfter.getCashBalance()).isEqualTo(cashBefore);
		assertThat(cancelException.get()).isNull();

		if (modifyException.get() != null) {
			assertThat(modifyException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_ALREADY_CANCELLED));
			assertThat(finalOrder.getLimitPrice()).isEqualByComparingTo(limitPrice);
		} else {
			assertThat(modifyResult.get()).isNotNull();
			assertThat(finalOrder.getLimitPrice()).isEqualByComparingTo(newLimitPrice);
		}
	}

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
			assertThat(modifyException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_ALREADY_FILLED));
			assertThat(finalOrder.getQuantity()).isEqualByComparingTo(sellQuantity);
			assertThat(quantityBefore.subtract(holdingAfter.getQuantity())).isEqualByComparingTo(sellQuantity);
		} else {
			assertThat(modifyResult.get()).isNotNull();
			assertThat(finalOrder.getQuantity()).isEqualByComparingTo(newQuantity);
			assertThat(quantityBefore.subtract(holdingAfter.getQuantity())).isEqualByComparingTo(newQuantity);
		}
	}

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
		assertThat(holdingAfter.getQuantity()).isEqualByComparingTo(quantityBefore);
		assertThat(cancelException.get()).isNull();

		if (modifyException.get() != null) {
			assertThat(modifyException.get()).isInstanceOf(BusinessException.class)
				.satisfies(
					ex -> assertThat(((BusinessException)ex).getErrorCode())
						.isEqualTo(ErrorCode.ORDER_ALREADY_CANCELLED));
			assertThat(finalOrder.getQuantity()).isEqualByComparingTo(sellQuantity);
		} else {
			assertThat(modifyResult.get()).isNotNull();
			assertThat(finalOrder.getQuantity()).isEqualByComparingTo(newQuantity);
		}
	}

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
