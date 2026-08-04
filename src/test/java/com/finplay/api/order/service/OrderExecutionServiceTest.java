// 시장가 매수·매도 주문의 검증·수수료 계산·현금증감·실현손익·실패 시 무흔적을 검증하는 단위 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.OrderExecutionPriceDto;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.PortfolioBuyService;
import com.finplay.api.portfolio.service.PortfolioSellService;
import com.finplay.api.portfolio.service.SellAllocationDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderExecutionServiceTest {

	private static final Long USER_ID = 1L;
	private static final String IDEMPOTENCY_KEY = "idem-key-1";
	private static final String REQUEST_HASH = "test-hash";
	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-29T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final UserQueryService userQueryService = mock(UserQueryService.class);
	private final AccountService accountService = mock(AccountService.class);
	private final InstrumentService instrumentService = mock(InstrumentService.class);
	private final PriceQueryService priceQueryService = mock(PriceQueryService.class);
	private final PortfolioBuyService portfolioBuyService = mock(PortfolioBuyService.class);
	private final PortfolioSellService portfolioSellService = mock(PortfolioSellService.class);
	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final TradeRepository tradeRepository = mock(TradeRepository.class);
	private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

	private OrderExecutionService orderExecutionService;

	@BeforeEach
	void setUp() {
		orderExecutionService = new OrderExecutionService(
			userQueryService,
			accountService,
			instrumentService,
			priceQueryService,
			portfolioBuyService,
			portfolioSellService,
			orderRepository,
			tradeRepository,
			clock);
	}

	@Test
	void createOrderCalculatesStockFeeWithFloorRoundingAndDeductsAmountPlusFeeFromCashOnBuy() {
		Instrument instrument = stockInstrument();
		Account account = account(com.finplay.api.account.domain.Market.STOCK);
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
		Account account = account(com.finplay.api.account.domain.Market.CRYPTO);
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
		Account account = account(com.finplay.api.account.domain.Market.STOCK);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		// amount = 50,000,000 * 1 > 계좌 기본 현금 10,000,000
		when(priceQueryService.getOrderExecutionPrice(instrument))
			.thenReturn(executionPrice(new BigDecimal("50000000"), mock(StockReplaySession.class)));
		when(accountService.getAccountFor(USER_ID, com.finplay.api.account.domain.Market.STOCK))
			.thenReturn(account);
		OrderCreateRequest request = buyRequest(Market.STOCK, instrument.getId(), "1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.INSUFFICIENT_CASH);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void createOrderSellCalculatesRealizedPnlAndAppliesCashAndRealizedPnlToAccount() {
		Instrument instrument = stockInstrument();
		Account account = account(com.finplay.api.account.domain.Market.STOCK);
		Holding holding = mock(Holding.class);
		User user = testUser();
		BigDecimal quantity = new BigDecimal("3");
		// price 10000 * 3 = amount 30000, fee = floor(30000*0.00015)=4
		stubSellHappyPath(instrument, account, user, new BigDecimal("10000"));
		when(portfolioSellService.getHoldingOrThrow(account, instrument, quantity)).thenReturn(holding);
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
		verify(portfolioSellService).getHoldingOrThrow(account, instrument, quantity);
		verify(portfolioSellService).applySellTrade(holding, savedTrade, quantity, NOW);
		verifyNoInteractions(portfolioBuyService);
	}

	@Test
	void createOrderSellAggregatesMultipleLotAllocationsIntoSingleRealizedPnl() {
		// PortfolioSellService가 여러 lot을 소비한 결과(합산된 원가·수수료)를 그대로 넘겨도
		// OrderExecutionService는 매도 1건 단위로 정확히 한 번만 realizedPnl을 계산해야 한다.
		Instrument instrument = stockInstrument();
		Account account = account(com.finplay.api.account.domain.Market.STOCK);
		Holding holding = mock(Holding.class);
		User user = testUser();
		BigDecimal quantity = new BigDecimal("8");
		stubSellHappyPath(instrument, account, user, new BigDecimal("300"));
		when(portfolioSellService.getHoldingOrThrow(account, instrument, quantity)).thenReturn(holding);
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
		Account account = account(com.finplay.api.account.domain.Market.CRYPTO);
		Holding holding = mock(Holding.class);
		User user = testUser();
		BigDecimal quantity = new BigDecimal("0.1");
		// price 133330 * 0.1 = 13333.0, fee = floor(13333*0.0005)=6
		stubSellHappyPath(instrument, account, user, new BigDecimal("133330"));
		when(portfolioSellService.getHoldingOrThrow(account, instrument, quantity)).thenReturn(holding);
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
		Account account = account(com.finplay.api.account.domain.Market.CRYPTO);
		Holding holding = mock(Holding.class);
		BigDecimal quantity = new BigDecimal("0.1");
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(accountService.getAccountFor(USER_ID, com.finplay.api.account.domain.Market.CRYPTO))
			.thenReturn(account);
		when(portfolioSellService.getHoldingOrThrow(account, instrument, quantity)).thenReturn(holding);
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
		Account account = account(com.finplay.api.account.domain.Market.STOCK);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(accountService.getAccountFor(USER_ID, com.finplay.api.account.domain.Market.STOCK))
			.thenReturn(account);
		when(portfolioSellService.getHoldingOrThrow(account, instrument, new BigDecimal("5")))
			.thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_QTY));
		OrderCreateRequest request = sellRequest(Market.STOCK, instrument.getId(), "5");

		assertThatThrownBy(() -> orderExecutionService.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_QTY));

		// 설계 노트 2: 보유수량 검증은 가격조회보다 먼저 일어나므로 시세 조회조차 발생하지 않는다.
		verifyNoInteractions(priceQueryService, userQueryService, orderRepository, tradeRepository,
			portfolioBuyService);
		verify(portfolioSellService, never())
			.applySellTrade(any(), any(), any(), any());
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(account.getRealizedPnl()).isEqualTo(0L);
	}

	@Test
	void createOrderSellDeactivatesHoldingWhenFullQuantitySold() {
		// PortfolioSellService의 실제 FIFO·원가 계산은 PortfolioSellServiceTest가 검증한다.
		// 여기서는 OrderExecutionService가 전량 매도 시에도 실제 Holding 상태 변화를 그대로 전달하는지만 확인한다.
		Instrument instrument = stockInstrument();
		Account account = account(com.finplay.api.account.domain.Market.STOCK);
		Holding holding = Holding.create(account, instrument, NOW.minusDays(1));
		holding.applyBuy(new BigDecimal("3"), new BigDecimal("100"), NOW.minusDays(1));
		User user = testUser();
		BigDecimal quantity = new BigDecimal("3");
		stubSellHappyPath(instrument, account, user, new BigDecimal("150"));
		when(portfolioSellService.getHoldingOrThrow(account, instrument, quantity)).thenReturn(holding);
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
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		StockReplaySession session = instrument.getMarket() == Market.STOCK ? mock(StockReplaySession.class) : null;
		when(priceQueryService.getOrderExecutionPrice(instrument)).thenReturn(executionPrice(price, session));
		com.finplay.api.account.domain.Market accountMarket = com.finplay.api.account.domain.Market
			.valueOf(instrument.getMarket().name());
		when(accountService.getAccountFor(USER_ID, accountMarket)).thenReturn(account);
		when(userQueryService.getUser(USER_ID)).thenReturn(user);
	}

	private void stubSellHappyPath(Instrument instrument, Account account, User user, BigDecimal price) {
		stubHappyPath(instrument, account, user, price);
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

	private static Account account(com.finplay.api.account.domain.Market market) {
		User user = testUser();
		return Account.create(user, market, NOW);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}
}
