// 시장가 매수 주문의 검증·수수료 계산·현금차감·실패 시 무흔적을 검증하는 단위 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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
import com.finplay.api.market.service.InstrumentService;
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
import com.finplay.api.portfolio.service.PortfolioBuyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderServiceTest {

	private static final Long USER_ID = 1L;
	private static final String IDEMPOTENCY_KEY = "idem-key-1";
	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-29T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final UserQueryService userQueryService = mock(UserQueryService.class);
	private final AccountService accountService = mock(AccountService.class);
	private final InstrumentService instrumentService = mock(InstrumentService.class);
	private final PriceQueryService priceQueryService = mock(PriceQueryService.class);
	private final PortfolioBuyService portfolioBuyService = mock(PortfolioBuyService.class);
	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final TradeRepository tradeRepository = mock(TradeRepository.class);
	private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

	private OrderService orderService;

	@BeforeEach
	void setUp() {
		orderService = new OrderService(
			userQueryService,
			accountService,
			instrumentService,
			priceQueryService,
			portfolioBuyService,
			orderRepository,
			tradeRepository,
			clock);
	}

	@Test
	void createBuyOrderCalculatesStockFeeWithFloorRoundingAndDeductsAmountPlusFeeFromCash() {
		Instrument instrument = stockInstrument();
		Account account = account(com.finplay.api.account.domain.Market.STOCK);
		User user = testUser();
		// rawAmount = 10000.33 * 3 = 30000.99 → 원단위 내림 30000, 수수료 30000*0.00015=4.5 → 내림 4 (나눠떨어지지 않음)
		stubHappyPath(instrument, account, user, new BigDecimal("10000.33"));
		OrderCreateRequest request = request(Market.STOCK, instrument.getId(), "3");

		OrderResponse response = orderService.createBuyOrder(USER_ID, IDEMPOTENCY_KEY, request);

		assertThat(response.amount()).isEqualTo(30000L);
		assertThat(response.fee()).isEqualTo(4L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L - 30004L);

		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		Trade savedTrade = tradeCaptor.getValue();
		assertThat(savedTrade.getAmount()).isEqualTo(30000L);
		assertThat(savedTrade.getFee()).isEqualTo(4L);
		verify(orderRepository).save(any(Order.class));
		verify(portfolioBuyService)
			.applyBuyTrade(account, instrument, savedTrade, new BigDecimal("3"), new BigDecimal("10000.33"), 4L, NOW);
	}

	@Test
	void createBuyOrderCalculatesCryptoFeeWithFloorRoundingAndDeductsAmountPlusFeeFromCash() {
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account(com.finplay.api.account.domain.Market.CRYPTO);
		User user = testUser();
		// rawAmount = 133330 * 0.1 = 13333.0, 수수료 13333*0.0005=6.6665 → 내림 6 (나눠떨어지지 않음)
		stubHappyPath(instrument, account, user, new BigDecimal("133330"));
		OrderCreateRequest request = request(Market.CRYPTO, instrument.getId(), "0.1");

		OrderResponse response = orderService.createBuyOrder(USER_ID, IDEMPOTENCY_KEY, request);

		assertThat(response.amount()).isEqualTo(13333L);
		assertThat(response.fee()).isEqualTo(6L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L - 13339L);
	}

	@Test
	void createBuyOrderThrowsValidationErrorWhenSideIsSell() {
		OrderCreateRequest request = new OrderCreateRequest(
			Market.STOCK, 1L, OrderSide.SELL, "MARKET", new BigDecimal("1"));

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(instrumentService, priceQueryService, accountService);
	}

	@Test
	void createBuyOrderThrowsUnsupportedOrderTypeWhenOrderTypeIsNotMarket() {
		OrderCreateRequest request = new OrderCreateRequest(
			Market.STOCK, 1L, OrderSide.BUY, "LIMIT", new BigDecimal("1"));

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.UNSUPPORTED_ORDER_TYPE);
		verifyNoInteractions(instrumentService, priceQueryService, accountService);
	}

	@Test
	void createBuyOrderThrowsValidationErrorWhenStockQuantityIsFractional() {
		Instrument instrument = stockInstrument();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		OrderCreateRequest request = request(Market.STOCK, instrument.getId(), "1.5");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(priceQueryService, accountService);
	}

	@Test
	void createBuyOrderThrowsValidationErrorWhenCryptoQuantityExceedsEightDecimals() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		OrderCreateRequest request = request(Market.CRYPTO, instrument.getId(), "0.123456789");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(priceQueryService, accountService);
	}

	@Test
	void createBuyOrderThrowsValidationErrorWhenQuantityIsZero() {
		Instrument instrument = stockInstrument();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		OrderCreateRequest request = request(Market.STOCK, instrument.getId(), "0");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(priceQueryService, accountService);
	}

	@Test
	void createBuyOrderThrowsValidationErrorWhenQuantityIsNegative() {
		Instrument instrument = stockInstrument();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		OrderCreateRequest request = request(Market.STOCK, instrument.getId(), "-5");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(priceQueryService, accountService);
	}

	@Test
	void createBuyOrderThrowsValidationErrorWhenCryptoOrderAmountBelowMinimum() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		// rawAmount = 40000 * 0.1 = 4000 < 5000 최소 주문금액
		when(priceQueryService.getPrice(instrument))
			.thenReturn(new PriceQuoteDto(new BigDecimal("40000"), NOW, PriceStatus.AVAILABLE, null));
		OrderCreateRequest request = request(Market.CRYPTO, instrument.getId(), "0.1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(accountService);
	}

	@Test
	void createBuyOrderThrowsValidationErrorWhenRequestMarketDoesNotMatchInstrumentMarket() {
		Instrument instrument = stockInstrument();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		OrderCreateRequest request = request(Market.CRYPTO, instrument.getId(), "1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.VALIDATION_ERROR);
		verifyNoInteractions(priceQueryService, accountService);
	}

	@Test
	void createBuyOrderThrowsMarketClosedWhenStockMarketIsClosed() {
		Instrument instrument = stockInstrument();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		doThrow(new BusinessException(ErrorCode.MARKET_CLOSED))
			.when(priceQueryService).assertOrderable(instrument);
		OrderCreateRequest request = request(Market.STOCK, instrument.getId(), "1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.MARKET_CLOSED);
		verifyNoInteractions(accountService);
	}

	@Test
	void createBuyOrderThrowsPriceUnavailableWhenStockPriceIsInvalid() {
		Instrument instrument = stockInstrument();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(priceQueryService.getPrice(instrument)).thenThrow(new BusinessException(ErrorCode.PRICE_UNAVAILABLE));
		OrderCreateRequest request = request(Market.STOCK, instrument.getId(), "1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.PRICE_UNAVAILABLE);
		verifyNoInteractions(accountService);
	}

	@Test
	void createBuyOrderThrowsPriceUnavailableWhenCryptoPriceIsInvalid() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(priceQueryService.getPrice(instrument)).thenThrow(new BusinessException(ErrorCode.PRICE_UNAVAILABLE));
		OrderCreateRequest request = request(Market.CRYPTO, instrument.getId(), "0.1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.PRICE_UNAVAILABLE);
		verifyNoInteractions(accountService);
	}

	@Test
	void createBuyOrderThrowsInsufficientCashWhenCashBalanceBelowAmountPlusFee() {
		Instrument instrument = stockInstrument();
		Account account = account(com.finplay.api.account.domain.Market.STOCK);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		// amount = 50,000,000 * 1 > 계좌 기본 현금 10,000,000
		when(priceQueryService.getPrice(instrument))
			.thenReturn(new PriceQuoteDto(new BigDecimal("50000000"), NOW, PriceStatus.AVAILABLE, null));
		when(accountService.getAccountFor(USER_ID, com.finplay.api.account.domain.Market.STOCK))
			.thenReturn(account);
		OrderCreateRequest request = request(Market.STOCK, instrument.getId(), "1");

		assertBusinessExceptionAndNoSideEffects(request, ErrorCode.INSUFFICIENT_CASH);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	private void assertBusinessExceptionAndNoSideEffects(OrderCreateRequest request, ErrorCode expectedErrorCode) {
		assertThatThrownBy(() -> orderService.createBuyOrder(USER_ID, IDEMPOTENCY_KEY, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(expectedErrorCode));

		verifyNoInteractions(userQueryService, orderRepository, tradeRepository, portfolioBuyService);
	}

	private void stubHappyPath(Instrument instrument, Account account, User user, BigDecimal price) {
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(priceQueryService.getPrice(instrument))
			.thenReturn(new PriceQuoteDto(price, NOW, PriceStatus.AVAILABLE, null));
		com.finplay.api.account.domain.Market accountMarket = com.finplay.api.account.domain.Market
			.valueOf(instrument.getMarket().name());
		when(accountService.getAccountFor(USER_ID, accountMarket)).thenReturn(account);
		when(userQueryService.getUser(USER_ID)).thenReturn(user);
	}

	private static OrderCreateRequest request(Market market, Long instrumentId, String quantity) {
		return new OrderCreateRequest(market, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
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
