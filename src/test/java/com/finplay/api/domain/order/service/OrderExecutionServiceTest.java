// 시장가 매수·매도 주문의 검증·수수료 계산·현금증감·실현손익·실패 시 무흔적을 검증하는 단위 테스트다.
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
		// rawAmount = 10000.33 * 3 = 30000.99 → 원단위 내림 30000, 수수료 30000*0.00015=4.5 → 내림 4 (나눠떨어지지 않음)
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
		// rawAmount = 133330 * 0.1 = 13333.0, 수수료 13333*0.0005=6.6665 → 내림 6 (나눠떨어지지 않음)
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

	// 036-remove-crypto-stale-status 회귀(구 PRICE-REST-004 승계) — PriceQueryService.getOrderExecutionPrice가
	// 관측 시각이 오래된(과거 032 시절엔 STALE) AVAILABLE quote를 돌려줘도, OrderExecutionService는 status를
	// 따로 판단하지 않고 그 가격 그대로 체결까지 진행해야 한다(경과 시간과 무관하게 항상 AVAILABLE만 받는다).
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

		// rawAmount = 133330 * 0.1 = 13333.0, 수수료 13333*0.0005=6.6665 → 내림 6
		assertThat(response.amount()).isEqualTo(13333L);
		assertThat(response.fee()).isEqualTo(6L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L - 13339L);
		verify(orderRepository).save(any(Order.class));
		verify(tradeRepository).save(any(Trade.class));
	}

	@Test
	void createOrderBuyDeductsFromTutorialAccountOnlyWhenInstrumentIsTutorialSample() {
		// spec 047 TUTORIAL-CASH-ISOL-002: 샌드박스 종목 매수의 현금 차감은 실제 Account가 아니라
		// 같은 사용자·시장의 튜토리얼 계좌에서 일어난다.
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

		// cashRequired = amount(30000) + fee(4) = 30004
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L); // 실제 계좌 현금은 전혀 변하지 않는다
		assertThat(account.getReservedCash()).isZero();
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L - 30004L); // 튜토리얼 계좌에서만 차감
	}

	@Test
	void createOrderBuyThrowsTutorialInsufficientCashRegardlessOfRealAccountBalanceAndLeavesBothAccountsUntouched() {
		// 047 TUTORIAL-CASH-ISOL-002·005: 샌드박스 매수는 실제 계좌 잔고가 넉넉해도 튜토리얼 계좌 잔고만
		// 보고 거부해야 하고, 오류 코드도 실제 계좌 부족(INSUFFICIENT_CASH)과 구분되는 TUTORIAL_INSUFFICIENT_CASH여야 한다.
		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account(Market.STOCK);
		account.addCash(50_000_000L); // 실제 계좌는 넉넉하다(6천만원) — 그래도 거부돼야 한다.
		User user = testUser();
		TutorialAccount tutorialAccount = TutorialAccount.create(
			user, Market.STOCK, NOW); // 기본 1000만원
		// amount = 12,000,000 * 1 = 12,000,000, fee = floor(12,000,000*0.00015) = 1800
		// cashRequired = 12,001,800 > 튜토리얼 계좌 잔고 10,000,000 (실제 계좌 잔고 60,000,000과는 무관)
		stubHappyPath(instrument, account, user, new BigDecimal("12000000"));
		when(tutorialAccountService.getOrCreateForUpdate(USER_ID, Market.STOCK,
			NOW))
			.thenReturn(tutorialAccount);
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "1");

		assertThatThrownBy(() -> orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.TUTORIAL_INSUFFICIENT_CASH));

		assertThat(account.getCashBalance()).isEqualTo(60_000_000L); // 실제 계좌 현금 불변
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L); // 튜토리얼 계좌도 차감되지 않음
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

		verifyNoInteractions(tutorialAccountService); // 047 회귀 방지: 실제 종목 매수는 튜토리얼 계좌를 전혀 조회하지 않는다
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
		// 설계 노트 2: orderType 검증은 side 무관하게 최상단에서 공유된다.
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
		// rawAmount = 40000 * 0.1 = 4000 < 5000 최소 주문금액
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
		// 이슈 #339 PR #341 리뷰 차단사항 — 031이 tradable=false 종목을 처음 만들면서 이 경로의 검증
		// 빈틈이 실제로 열렸다(즐겨찾기는 이미 막혀 있었으나 일반 주문 경로는 검증이 없었음).
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
		// amount = 50,000,000 * 1 > 계좌 기본 현금 10,000,000
		when(priceQueryService.getOrderExecutionPrice(instrument))
			.thenReturn(executionPrice(new BigDecimal("50000000"), mock(StockReplaySession.class)));
		when(accountService.getAccountForUpdate(USER_ID, Market.STOCK))
			.thenReturn(account);
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.INSUFFICIENT_CASH);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		verifyNoInteractions(tutorialAccountService); // 047 회귀 방지: 실제 종목 매수는 튜토리얼 계좌를 전혀 조회하지 않는다
	}

	@Test
	void createOrderThrowsInsufficientCashWhenAvailableCashBelowAmountPlusFeeEvenIfCashBalanceSuffices() {
		// 이슈 #224 회귀 테스트 — 현금 검증이 cashBalance만 보고 지정가 매수 예약분(reservedCash)을
		// 반영하지 않으면, cashBalance는 충분한데 availableCash는 부족한 이 케이스에서 거부에 실패한다.
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		account.reserveCash(9_950_000L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		// amount = 100000 * 1 = 100000, 수수료 floor(100000*0.00015)=15 → cashRequired=100115
		// cashBalance(10,000,000) >= cashRequired지만 availableCash(10,000,000-9,950,000=50,000) < cashRequired
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
		// price 10000 * 3 = amount 30000, fee = floor(30000*0.00015)=4
		stubSellHappyPath(instrument, account, user, new BigDecimal("10000"));
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)).thenReturn(holding);
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(quantity), eq(NOW)))
			.thenReturn(new SellAllocationDto(20_000L, 3L));
		OrderCreateRequest request = sellRequest(Market.STOCK, instrument.getId(), "3");

		OrderResponse response = orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(response.amount()).isEqualTo(30000L);
		assertThat(response.fee()).isEqualTo(4L);
		// realizedPnl = (30000 - 4) - (20000 + 3) = 9993
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
		// spec 047 TUTORIAL-CASH-ISOL-003(033 SANDBOX-EXCL-004·006 대체): 샌드박스 종목 시장가 매도는 실제
		// Account.cashBalance·realizedPnl을 전혀 증가시키지 않는다 — 대신 같은 사용자·시장의 튜토리얼 계좌
		// 현금·realizedPnl이 갱신된다. trade.realizedPnl(원장 값)은 종목 종류와 무관하게 항상 채워진다.
		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account(Market.STOCK);
		Holding holding = mock(Holding.class);
		User user = testUser();
		TutorialAccount tutorialAccount = TutorialAccount.create(
			user, Market.STOCK, NOW);
		BigDecimal quantity = new BigDecimal("3");
		// price 10000 * 3 = amount 30000, fee = floor(30000*0.00015)=4
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
		// 실제 Account 현금은 이슈 #450 재발 방지 핵심 전제대로 전혀 변하지 않는다.
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		// 튜토리얼 계좌만 매도 대금·실현손익을 반영한다.
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L + 30000L - 4L);
		assertThat(tutorialAccount.getRealizedPnl()).isEqualTo(9993L);
		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		// realizedPnl = (30000 - 4) - (20000 + 3) = 9993
		assertThat(tradeCaptor.getValue().getRealizedPnl()).isEqualTo(9993L);
	}

	@Test
	void createOrderSellPublishesRealizedPnlUpdatedEventAfterAddingRealizedPnl() {
		// 랭킹 갱신(after-commit 리스너)이 반응할 수 있도록 SELL 체결 시 이벤트가 정확히 1회 발행되는지 검증한다.
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		ReflectionTestUtils.setField(account, "id", 42L); // id 미설정 시 기대값·실제값 모두 null이라 단정이 무의미해짐(PR #196 리뷰 지적)
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
		// 매수는 realizedPnl을 갱신하지 않으므로 랭킹 갱신 이벤트를 발행하지 않아야 한다.
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
		// PortfolioSellService가 여러 lot을 소비한 결과(합산된 원가·수수료)를 그대로 넘겨도
		// OrderExecutionService는 매도 1건 단위로 정확히 한 번만 realizedPnl을 계산해야 한다.
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		Holding holding = mock(Holding.class);
		User user = testUser();
		BigDecimal quantity = new BigDecimal("8");
		stubSellHappyPath(instrument, account, user, new BigDecimal("300"));
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)).thenReturn(holding);
		// lot1 전량(500원가+15수수료) + lot2 부분(600원가+18수수료) 합산 결과라고 가정
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(quantity), eq(NOW)))
			.thenReturn(new SellAllocationDto(500L + 600L, 15L + 18L));
		OrderCreateRequest request = sellRequest(Market.STOCK, instrument.getId(), "8");

		OrderResponse response = orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		// amount = 300*8=2400, fee=floor(2400*0.00015)=0
		assertThat(response.amount()).isEqualTo(2400L);
		assertThat(response.fee()).isEqualTo(0L);
		// realizedPnl = (2400 - 0) - (1100 + 33) = 1267
		assertThat(account.getRealizedPnl()).isEqualTo(1267L);
	}

	@Test
	void createOrderSellAbsorbsRoundingRemainderExactlyInRealizedPnlFormula() {
		// 원 단위 잔여 처리 경계값: 나눠떨어지지 않는 금액이어도 long 뺄셈만으로 정확히 계산되는지 확인한다.
		Instrument instrument = cryptoInstrument(0L);
		Account account = account(Market.CRYPTO);
		Holding holding = mock(Holding.class);
		User user = testUser();
		BigDecimal quantity = new BigDecimal("0.1");
		// price 133330 * 0.1 = 13333.0, fee = floor(13333*0.0005)=6
		stubSellHappyPath(instrument, account, user, new BigDecimal("133330"));
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)).thenReturn(holding);
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(quantity), eq(NOW)))
			.thenReturn(new SellAllocationDto(10_001L, 7L));
		OrderCreateRequest request = sellRequest(Market.CRYPTO, instrument.getId(), "0.1");

		OrderResponse response = orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(response.amount()).isEqualTo(13333L);
		assertThat(response.fee()).isEqualTo(6L);
		// realizedPnl = (13333 - 6) - (10001 + 7) = 3319
		assertThat(account.getRealizedPnl()).isEqualTo(3319L);
	}

	@Test
	void createOrderThrowsValidationErrorWhenCryptoOrderAmountBelowMinimumOnSell() {
		// 최소주문금액 검증은 BUY·SELL 공유 로직(priceOrder)이지만 회귀 방지를 위해 SELL 경로도 명시적으로 검증한다.
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account(Market.CRYPTO);
		Holding holding = mock(Holding.class);
		BigDecimal quantity = new BigDecimal("0.1");
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(accountService.getAccountForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity)).thenReturn(holding);
		// rawAmount = 40000 * 0.1 = 4000 < 5000 최소 주문금액
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

		// 설계 노트 2: 계좌 락 이후 보유수량 검증이 가격조회보다 먼저 일어나므로 시세 조회조차 발생하지 않는다.
		verifyNoInteractions(priceQueryService, userQueryService, orderRepository, tradeRepository,
			portfolioBuyService);
		verify(portfolioSellService, never())
			.applySellTrade(any(), any(), any(), any());
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(account.getRealizedPnl()).isEqualTo(0L);
	}

	@Test
	void createOrderSellRejectsOverSellWhenReservedQuantityMakesAvailableQuantityInsufficient() {
		// spec.md: 지정가로 예약된 수량은 시장가 매도로 초과 매도할 수 없다. availableQuantity(=quantity-reservedQuantity)
		// 기준 검증 자체는 PortfolioSellServiceTest.getHoldingForUpdateOrThrow...가 실물 Holding으로 증명한다 —
		// 여기서는 그 결과(INSUFFICIENT_QTY)를 OrderExecutionService가 그대로 전파하며 가격조회·저장을 하지 않는지 확인한다.
		Instrument instrument = stockInstrument();
		Account account = account(Market.STOCK);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(accountService.getAccountForUpdate(USER_ID, Market.STOCK))
			.thenReturn(account);
		// 보유 5주 중 3주가 다른 지정가 매도로 이미 예약된 상태(availableQuantity=2) — 3주 시장가 매도는 거부돼야 한다.
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
		// PortfolioSellService의 실제 FIFO·원가 계산은 PortfolioSellServiceTest가 검증한다.
		// 여기서는 OrderExecutionService가 전량 매도 시에도 실제 Holding 상태 변화를 그대로 전달하는지만 확인한다.
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

	// getMyOrders 관련 테스트는 OrderService에 그대로 남아 있으므로(변경 없음) 이 클래스로 옮기지 않는다.
	// OrderService 자체의 슬림 테스트(createOrder 위임·getMyOrders)는 이슈 #22 항목 3에서 새로 작성된다.

	private void assertBusinessExceptionAndNoSideEffects(OrderCreateRequest request, ErrorCode expectedErrorCode) {
		assertThatThrownBy(() -> orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(expectedErrorCode));

		verifyNoInteractions(userQueryService, orderRepository, tradeRepository, portfolioBuyService,
			portfolioSellService);
	}

	private void stubHappyPath(Instrument instrument, Account account, User user, BigDecimal price) {
		// 매수도 매도와 동일하게 계좌를 락으로 조회한다(015-limit-order 시장가 매수 경로 락 보강, 이슈 #224).
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		StockReplaySession session = instrument.getMarket() == Market.STOCK ? mock(StockReplaySession.class) : null;
		when(priceQueryService.getOrderExecutionPrice(instrument)).thenReturn(executionPrice(price, session));
		com.finplay.api.domain.market.entity.Market accountMarket = com.finplay.api.domain.market.entity.Market
			.valueOf(instrument.getMarket().name());
		when(accountService.getAccountForUpdate(USER_ID, accountMarket)).thenReturn(account);
		when(userQueryService.getUser(USER_ID)).thenReturn(user);
	}

	private void stubSellHappyPath(Instrument instrument, Account account, User user, BigDecimal price) {
		// 매도·매수 모두 계좌를 락으로 조회한다(015-limit-order 항목5·이슈 #224) — stubHappyPath와 별도 메서드로
		// 유지하는 이유는 SELL 전용 파라미터(holding 스텁 등) 확장 여지 때문이며, 스텁 대상 메서드 자체는 동일하다.
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
