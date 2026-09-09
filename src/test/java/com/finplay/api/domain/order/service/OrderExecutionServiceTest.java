package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.OrderExecutionPriceDto;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.market.service.PriceQuoteDto;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioBuyService;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.domain.portfolio.service.SellAllocationDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class OrderExecutionServiceTest {

	private static final Long USER_ID = 1L;
	private static final String IDEMPOTENCY_KEY = "idem-key-1";
	private static final String REQUEST_HASH = "test-hash";
	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-29T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final UserQueryService userQueryService = mock(UserQueryService.class);
	private final AccountService accountService = mock(AccountService.class);
	private final TutorialAccountService tutorialAccountService = mock(TutorialAccountService.class);
	private final InstrumentService instrumentService = mock(InstrumentService.class);
	private final PriceQueryService priceQueryService = mock(PriceQueryService.class);
	private final PortfolioBuyService portfolioBuyService = mock(PortfolioBuyService.class);
	private final PortfolioSellService portfolioSellService = mock(PortfolioSellService.class);
	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final TradeRepository tradeRepository = mock(TradeRepository.class);
	private final PracticeOrderSettlementService practiceOrderSettlementService = mock(
		PracticeOrderSettlementService.class);
	private final PracticeOrderAttributionPort practiceOrderAttributionPort = mock(
		PracticeOrderAttributionPort.class);
	private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
	private final org.springframework.context.ApplicationEventPublisher eventPublisher = mock(
		org.springframework.context.ApplicationEventPublisher.class);

	private OrderExecutionService orderExecutionService;

	@BeforeEach
	void setUp() {
		orderExecutionService = new OrderExecutionService(
			userQueryService,
			accountService,
			tutorialAccountService,
			instrumentService,
			priceQueryService,
			portfolioBuyService,
			portfolioSellService,
			orderRepository,
			tradeRepository,
			practiceOrderAttributionPort,
			practiceOrderSettlementService,
			clock,
			eventPublisher);
	}

	@Test
	void createOrderCalculatesStockFeeWithFloorRoundingAndDeductsAmountPlusFeeFromCashOnBuy() {
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		User user = testUser();
		stubHappyPath(instrument, account, user, new BigDecimal("10000.33"));
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "3");

		OrderResponse response = orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(response.amount()).isEqualTo(30000L);
		assertThat(response.fee()).isEqualTo(4L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L - 30004L);

		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		Trade savedTrade = tradeCaptor.getValue();
		assertThat(savedTrade.getAmount()).isEqualTo(30000L);
		assertThat(savedTrade.getFee()).isEqualTo(4L);
		assertThat(savedTrade.getStockReplaySession()).isNotNull();
		verify(orderRepository).save(any(Order.class));
		verify(portfolioBuyService)
			.applyBuyTrade(account, instrument, savedTrade, new BigDecimal("3"), new BigDecimal("10000.33"), 4L, NOW);
		verifyNoInteractions(portfolioSellService);
	}

	@Test
	void createOrderCalculatesCryptoFeeWithFloorRoundingAndDeductsAmountPlusFeeFromCashOnBuy() {
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account(Market.CRYPTO);
		User user = testUser();
		stubHappyPath(instrument, account, user, new BigDecimal("133330"));
		OrderCreateRequest request = buyRequest(Market.CRYPTO, instrument.getId(), "0.1");

		OrderResponse response = orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(response.amount()).isEqualTo(13333L);
		assertThat(response.fee()).isEqualTo(6L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L - 13339L);
		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		assertThat(tradeCaptor.getValue().getStockReplaySession()).isNull();
	}

	@Test
	void createOrderExecutesCryptoMarketBuyToCompletionRegardlessOfExecutionPriceObservationAge() {
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account(Market.CRYPTO);
		User user = testUser();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(priceQueryService.getOrderExecutionPrice(instrument)).thenReturn(
			new OrderExecutionPriceDto(
				new PriceQuoteDto(new BigDecimal("133330"), NOW.minusHours(3), PriceStatus.AVAILABLE, null), null));
		when(accountService.getAccountForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(userQueryService.getUser(USER_ID)).thenReturn(user);
		OrderCreateRequest request = buyRequest(Market.CRYPTO, instrument.getId(), "0.1");

		OrderResponse response = orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(response.amount()).isEqualTo(13333L);
		assertThat(response.fee()).isEqualTo(6L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L - 13339L);
		verify(orderRepository).save(any(Order.class));
		verify(tradeRepository).save(any(Trade.class));
	}

	@Test
	void createOrderBuyDeductsFromTutorialAccountOnlyWhenInstrumentIsTutorialSample() {
		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account(Market.STOCK);
		User user = testUser();
		TutorialAccount tutorialAccount = TutorialAccount.create(
			user, Market.STOCK, NOW);
		stubHappyPath(instrument, account, user, new BigDecimal("10000.33"));
		when(tutorialAccountService.getOrCreateForUpdate(USER_ID, Market.STOCK,
			NOW))
			.thenReturn(tutorialAccount);
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "3");

		orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(account.getReservedCash()).isZero();
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L - 30004L);
	}

	@Test
	void createOrderBuyThrowsTutorialInsufficientCashRegardlessOfRealAccountBalanceAndLeavesBothAccountsUntouched() {
		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account(Market.STOCK);
		account.addCash(50_000_000L);
		User user = testUser();
		TutorialAccount tutorialAccount = TutorialAccount.create(
			user, Market.STOCK, NOW);
		stubHappyPath(instrument, account, user, new BigDecimal("12000000"));
		when(tutorialAccountService.getOrCreateForUpdate(USER_ID, Market.STOCK,
			NOW))
			.thenReturn(tutorialAccount);
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "1");

		assertThatThrownBy(() -> orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.TUTORIAL_INSUFFICIENT_CASH));

		assertThat(account.getCashBalance()).isEqualTo(60_000_000L);
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L);
		verify(orderRepository, never()).save(any());
		verify(tradeRepository, never()).save(any());
	}

	@Test
	void createTutorialSampleOrderStoresCurrentAttemptAttribution() {
		Instrument instrument = cryptoInstrument(5_000L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account(Market.CRYPTO);
		User user = testUser();
		stubHappyPath(instrument, account, user, new BigDecimal("10000"));
		when(practiceOrderAttributionPort.lockForOrder(USER_ID, instrument, OrderType.MARKET))
			.thenReturn(Optional.of(new PracticeOrderAttributionDto(50L, 3L, new BigDecimal("10000"))));
		OrderCreateRequest request = buyRequest(Market.CRYPTO, instrument.getId(), "1");

		orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getPracticeAttemptId()).isEqualTo(50L);
		assertThat(orderCaptor.getValue().getPracticeAttemptRunNumber()).isEqualTo(3L);
		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		assertThat(tradeCaptor.getValue().getPrice()).isEqualByComparingTo("10000");
		verifyNoInteractions(priceQueryService);
	}

	@Test
	void createOrdinaryOrderKeepsAttemptAttributionNull() {
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account(Market.CRYPTO);
		User user = testUser();
		stubHappyPath(instrument, account, user, new BigDecimal("10000"));
		when(practiceOrderAttributionPort.lockForOrder(USER_ID, instrument, OrderType.MARKET))
			.thenReturn(Optional.empty());
		OrderCreateRequest request = buyRequest(Market.CRYPTO, instrument.getId(), "1");

		orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getPracticeAttemptId()).isNull();
		assertThat(orderCaptor.getValue().getPracticeAttemptRunNumber()).isNull();
	}

	@Test
	void createOrderBuyDoesNotTouchTutorialAccountWhenInstrumentIsReal() {
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		User user = testUser();
		stubHappyPath(instrument, account, user, new BigDecimal("10000.33"));
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "3");

		orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		verifyNoInteractions(tutorialAccountService);
	}

	@Test
	void createOrderThrowsUnsupportedOrderTypeWhenOrderTypeIsNotMarket() {
		OrderCreateRequest request = new OrderCreateRequest(
			Market.STOCK, 1L, OrderSide.BUY, "LIMIT", new BigDecimal("1"));

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.UNSUPPORTED_ORDER_TYPE);
		verifyNoInteractions(instrumentService, priceQueryService, accountService);
	}

	@Test
	void createOrderThrowsUnsupportedOrderTypeWhenSideIsSellAndOrderTypeIsNotMarket() {
		OrderCreateRequest request = new OrderCreateRequest(
			Market.STOCK, 1L, OrderSide.SELL, "LIMIT", new BigDecimal("1"));

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.UNSUPPORTED_ORDER_TYPE);
		verifyNoInteractions(instrumentService, priceQueryService, accountService);
	}

	@Test
	void createOrderThrowsValidationErrorWhenStockQuantityIsFractional() {
		Instrument instrument = stockInstrument();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "1.5");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(priceQueryService, accountService);
	}

	@Test
	void createOrderThrowsValidationErrorWhenCryptoQuantityExceedsEightDecimals() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		OrderCreateRequest request = buyRequest(Market.CRYPTO, instrument.getId(), "0.123456789");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(priceQueryService, accountService);
	}

	@Test
	void createOrderThrowsValidationErrorWhenQuantityIsZero() {
		Instrument instrument = stockInstrument();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "0");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(priceQueryService, accountService);
	}

	@Test
	void createOrderThrowsValidationErrorWhenQuantityIsNegative() {
		Instrument instrument = stockInstrument();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "-5");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(priceQueryService, accountService);
	}

	@Test
	void createOrderThrowsValidationErrorWhenCryptoOrderAmountBelowMinimumOnBuy() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(priceQueryService.getOrderExecutionPrice(instrument))
			.thenReturn(executionPrice(new BigDecimal("40000"), null));
		OrderCreateRequest request = buyRequest(Market.CRYPTO, instrument.getId(), "0.1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
	}

	@Test
	void createOrderThrowsValidationErrorWhenRequestMarketDoesNotMatchInstrumentMarket() {
		Instrument instrument = stockInstrument();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		OrderCreateRequest request = buyRequest(Market.CRYPTO, instrument.getId(), "1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(priceQueryService, accountService);
	}

	@Test
	void createOrderThrowsInstrumentNotTradableWhenInstrumentIsNotTradable() {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, false, NOW);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.INSTRUMENT_NOT_TRADABLE);
		verifyNoInteractions(priceQueryService, accountService);
	}

	@Test
	void createOrderThrowsMarketClosedWhenStockMarketIsClosed() {
		Instrument instrument = stockInstrument();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(priceQueryService.getOrderExecutionPrice(instrument))
			.thenThrow(new BusinessException(ErrorCode.MARKET_CLOSED));
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.MARKET_CLOSED);
	}

	@Test
	void createOrderThrowsPriceUnavailableWhenStockPriceIsInvalid() {
		Instrument instrument = stockInstrument();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(priceQueryService.getOrderExecutionPrice(instrument))
			.thenThrow(new BusinessException(ErrorCode.PRICE_UNAVAILABLE));
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.PRICE_UNAVAILABLE);
	}

	@Test
	void createOrderThrowsPriceUnavailableWhenCryptoPriceIsInvalid() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(priceQueryService.getOrderExecutionPrice(instrument))
			.thenThrow(new BusinessException(ErrorCode.PRICE_UNAVAILABLE));
		OrderCreateRequest request = buyRequest(Market.CRYPTO, instrument.getId(), "0.1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.PRICE_UNAVAILABLE);
	}

	@Test
	void createOrderThrowsInsufficientCashWhenCashBalanceBelowAmountPlusFee() {
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(priceQueryService.getOrderExecutionPrice(instrument))
			.thenReturn(executionPrice(new BigDecimal("50000000"), mock(StockReplaySession.class)));
		when(accountService.getAccountForUpdate(USER_ID, Market.STOCK))
			.thenReturn(account);
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.INSUFFICIENT_CASH);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		verifyNoInteractions(tutorialAccountService);
	}

	@Test
	void createOrderThrowsInsufficientCashWhenAvailableCashBelowAmountPlusFeeEvenIfCashBalanceSuffices() {
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		account.reserveCash(9_950_000L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(priceQueryService.getOrderExecutionPrice(instrument))
			.thenReturn(executionPrice(new BigDecimal("100000"), mock(StockReplaySession.class)));
		when(accountService.getAccountForUpdate(USER_ID, Market.STOCK))
			.thenReturn(account);
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.INSUFFICIENT_CASH);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void createOrderSellCalculatesRealizedPnlAndAppliesCashAndRealizedPnlToAccount() {
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		Holding holding = mock(Holding.class);
		User user = testUser();
		BigDecimal quantity = new BigDecimal("3");
		stubSellHappyPath(instrument, account, user, new BigDecimal("10000"));
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)).thenReturn(holding);
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(quantity), eq(NOW)))
			.thenReturn(new SellAllocationDto(20_000L, 3L));
		OrderCreateRequest request = sellRequest(Market.STOCK, instrument.getId(), "3");

		OrderResponse response = orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(response.amount()).isEqualTo(30000L);
		assertThat(response.fee()).isEqualTo(4L);
		assertThat(account.getRealizedPnl()).isEqualTo(9993L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L + 30000L - 4L);

		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		Trade savedTrade = tradeCaptor.getValue();
		assertThat(savedTrade.getRealizedPnl()).isEqualTo(9993L);
		verify(orderRepository).save(any(Order.class));
		verify(portfolioSellService).getHoldingForUpdateOrThrow(account, instrument, quantity);
		verify(portfolioSellService).applySellTrade(holding, savedTrade, quantity, NOW);
		verifyNoInteractions(portfolioBuyService);
	}

	@Test
	void createOrderSellCreditsTutorialAccountAndLeavesRealAccountCashAndRealizedPnlUnchangedWhenInstrumentIsTutorialSample() {
		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account(Market.STOCK);
		Holding holding = mock(Holding.class);
		User user = testUser();
		TutorialAccount tutorialAccount = TutorialAccount.create(
			user, Market.STOCK, NOW);
		BigDecimal quantity = new BigDecimal("3");
		stubSellHappyPath(instrument, account, user, new BigDecimal("10000"));
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)).thenReturn(holding);
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(quantity), eq(NOW)))
			.thenReturn(new SellAllocationDto(20_000L, 3L));
		when(tutorialAccountService.getOrCreateForUpdate(USER_ID, Market.STOCK,
			NOW))
			.thenReturn(tutorialAccount);
		OrderCreateRequest request = sellRequest(Market.STOCK, instrument.getId(), "3");

		orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(account.getRealizedPnl()).isEqualTo(0L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L + 30000L - 4L);
		assertThat(tutorialAccount.getRealizedPnl()).isEqualTo(9993L);
		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		assertThat(tradeCaptor.getValue().getRealizedPnl()).isEqualTo(9993L);
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void createOrderSellPublishesRealizedPnlUpdatedEventAfterAddingRealizedPnl() {
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		ReflectionTestUtils.setField(account, "id", 42L);
		Holding holding = mock(Holding.class);
		User user = testUser();
		BigDecimal quantity = new BigDecimal("3");
		stubSellHappyPath(instrument, account, user, new BigDecimal("10000"));
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)).thenReturn(holding);
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(quantity), eq(NOW)))
			.thenReturn(new SellAllocationDto(20_000L, 3L));
		OrderCreateRequest request = sellRequest(Market.STOCK, instrument.getId(), "3");

		orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		ArgumentCaptor<RealizedPnlUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(RealizedPnlUpdatedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().accountId()).isEqualTo(42L);
	}

	@Test
	void createOrderBuyDoesNotPublishRealizedPnlUpdatedEvent() {
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		User user = testUser();
		stubHappyPath(instrument, account, user, new BigDecimal("10000.33"));
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "3");

		orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		verifyNoInteractions(eventPublisher);
	}

	@Test
	void createOrderSellAggregatesMultipleLotAllocationsIntoSingleRealizedPnl() {
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		Holding holding = mock(Holding.class);
		User user = testUser();
		BigDecimal quantity = new BigDecimal("8");
		stubSellHappyPath(instrument, account, user, new BigDecimal("300"));
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)).thenReturn(holding);
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(quantity), eq(NOW)))
			.thenReturn(new SellAllocationDto(500L + 600L, 15L + 18L));
		OrderCreateRequest request = sellRequest(Market.STOCK, instrument.getId(), "8");

		OrderResponse response = orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(response.amount()).isEqualTo(2400L);
		assertThat(response.fee()).isEqualTo(0L);
		assertThat(account.getRealizedPnl()).isEqualTo(1267L);
	}

	@Test
	void createOrderSellAbsorbsRoundingRemainderExactlyInRealizedPnlFormula() {
		Instrument instrument = cryptoInstrument(0L);
		Account account = account(Market.CRYPTO);
		Holding holding = mock(Holding.class);
		User user = testUser();
		BigDecimal quantity = new BigDecimal("0.1");
		stubSellHappyPath(instrument, account, user, new BigDecimal("133330"));
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)).thenReturn(holding);
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(quantity), eq(NOW)))
			.thenReturn(new SellAllocationDto(10_001L, 7L));
		OrderCreateRequest request = sellRequest(Market.CRYPTO, instrument.getId(), "0.1");

		OrderResponse response = orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(response.amount()).isEqualTo(13333L);
		assertThat(response.fee()).isEqualTo(6L);
		assertThat(account.getRealizedPnl()).isEqualTo(3319L);
	}

	@Test
	void createOrderThrowsValidationErrorWhenCryptoOrderAmountBelowMinimumOnSell() {
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account(Market.CRYPTO);
		Holding holding = mock(Holding.class);
		BigDecimal quantity = new BigDecimal("0.1");
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(accountService.getAccountForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)).thenReturn(holding);
		when(priceQueryService.getOrderExecutionPrice(instrument))
			.thenReturn(executionPrice(new BigDecimal("40000"), null));
		OrderCreateRequest request = sellRequest(Market.CRYPTO, instrument.getId(), "0.1");

		assertThatThrownBy(() -> orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(userQueryService, orderRepository, tradeRepository, portfolioBuyService);
		verify(portfolioSellService, never()).applySellTrade(any(), any(), any(), any());
	}

	@Test
	void createOrderSellThrowsInsufficientQtyAndNeverQueriesPriceOrSavesAnything() {
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(accountService.getAccountForUpdate(USER_ID, Market.STOCK))
			.thenReturn(account);
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, new BigDecimal("5")))
			.thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_QTY));
		OrderCreateRequest request = sellRequest(Market.STOCK, instrument.getId(), "5");

		assertThatThrownBy(() -> orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_QTY));

		verifyNoInteractions(priceQueryService, userQueryService, orderRepository, tradeRepository,
			portfolioBuyService);
		verify(portfolioSellService, never())
			.applySellTrade(any(), any(), any(), any());
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(account.getRealizedPnl()).isEqualTo(0L);
	}

	@Test
	void createOrderSellRejectsOverSellWhenReservedQuantityMakesAvailableQuantityInsufficient() {
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(accountService.getAccountForUpdate(USER_ID, Market.STOCK))
			.thenReturn(account);
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, new BigDecimal("3")))
			.thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_QTY));
		OrderCreateRequest request = sellRequest(Market.STOCK, instrument.getId(), "3");

		assertThatThrownBy(() -> orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_QTY));

		verifyNoInteractions(priceQueryService, userQueryService, orderRepository, tradeRepository,
			portfolioBuyService);
		verify(portfolioSellService, never()).applySellTrade(any(), any(), any(), any());
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void createOrderSellDeactivatesHoldingWhenFullQuantitySold() {
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		Holding holding = Holding.create(account, instrument, NOW.minusDays(1));
		holding.applyBuy(new BigDecimal("3"), new BigDecimal("100"), NOW.minusDays(1));
		User user = testUser();
		BigDecimal quantity = new BigDecimal("3");
		stubSellHappyPath(instrument, account, user, new BigDecimal("150"));
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)).thenReturn(holding);
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(quantity), eq(NOW)))
			.thenAnswer(invocation -> {
				holding.applySell(quantity, NOW);
				return new SellAllocationDto(300L, 0L);
			});
		OrderCreateRequest request = sellRequest(Market.STOCK, instrument.getId(), "3");

		orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(holding.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(holding.isActive()).isFalse();
	}

	private void assertBusinessExceptionAndNoSideEffects(OrderCreateRequest request, ErrorCode expectedErrorCode) {
		assertThatThrownBy(() -> orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(expectedErrorCode));

		verifyNoInteractions(userQueryService, orderRepository, tradeRepository, portfolioBuyService,
			portfolioSellService);
	}

	private void stubHappyPath(Instrument instrument, Account account, User user, BigDecimal price) {
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		StockReplaySession session = instrument.getMarket() == Market.STOCK ? mock(StockReplaySession.class) : null;
		when(priceQueryService.getOrderExecutionPrice(instrument)).thenReturn(executionPrice(price, session));
		com.finplay.api.domain.market.entity.Market accountMarket = com.finplay.api.domain.market.entity.Market
			.valueOf(instrument.getMarket().name());
		when(accountService.getAccountForUpdate(USER_ID, accountMarket)).thenReturn(account);
		when(userQueryService.getUser(USER_ID)).thenReturn(user);
	}

	private void stubSellHappyPath(Instrument instrument, Account account, User user, BigDecimal price) {
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		StockReplaySession session = instrument.getMarket() == Market.STOCK ? mock(StockReplaySession.class) : null;
		when(priceQueryService.getOrderExecutionPrice(instrument)).thenReturn(executionPrice(price, session));
		com.finplay.api.domain.market.entity.Market accountMarket = com.finplay.api.domain.market.entity.Market
			.valueOf(instrument.getMarket().name());
		when(accountService.getAccountForUpdate(USER_ID, accountMarket)).thenReturn(account);
		when(userQueryService.getUser(USER_ID)).thenReturn(user);
	}

	private static OrderExecutionPriceDto executionPrice(BigDecimal price, StockReplaySession session) {
		return new OrderExecutionPriceDto(new PriceQuoteDto(price, NOW, PriceStatus.AVAILABLE, null), session);
	}

	private static OrderCreateRequest buyRequest(Market market, Long instrumentId, String quantity) {
		return new OrderCreateRequest(market, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
	}

	private static OrderCreateRequest sellRequest(Market market, Long instrumentId, String quantity) {
		return new OrderCreateRequest(market, instrumentId, OrderSide.SELL, "MARKET", new BigDecimal(quantity));
	}

	private static Instrument stockInstrument() {
		return Instrument.create(Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, NOW);
	}

	private static Instrument cryptoInstrument(long minOrderAmount) {
		return Instrument.create(Market.CRYPTO, "BTC", "비트코인", new BigDecimal("0.00000001"), minOrderAmount, true, NOW);
	}

	private static Account account(com.finplay.api.domain.market.entity.Market market) {
		User user = testUser();
		return Account.create(user, market, NOW);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}
}
