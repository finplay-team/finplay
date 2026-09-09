package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class LimitOrderCreationServiceTest {

	private static final Long USER_ID = 1L;
	private static final String IDEMPOTENCY_KEY = "idem-limit-1";
	private static final String REQUEST_HASH = "test-hash";
	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final UserQueryService userQueryService = mock(UserQueryService.class);
	private final AccountService accountService = mock(AccountService.class);
	private final TutorialAccountService tutorialAccountService = mock(TutorialAccountService.class);
	private final InstrumentService instrumentService = mock(InstrumentService.class);
	private final PortfolioSellService portfolioSellService = mock(PortfolioSellService.class);
	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final PracticeOrderAttributionPort practiceOrderAttributionPort = mock(
		PracticeOrderAttributionPort.class);
	private final PracticeOrderSettlementService practiceOrderSettlementService = mock(
		PracticeOrderSettlementService.class);
	private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

	private final LimitOrderCreationService service = new LimitOrderCreationService(
		userQueryService, accountService, tutorialAccountService, instrumentService, portfolioSellService,
		orderRepository, practiceOrderAttributionPort, practiceOrderSettlementService, clock);

	@Test
	void createLimitOrderBuyReservesCashRequiredAndCreatesPendingOrder() {
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(practiceOrderAttributionPort.lockForOrder(USER_ID, instrument, OrderType.LIMIT))
			.thenReturn(Optional.empty());
		when(accountService.getAccountForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(userQueryService.getUser(USER_ID)).thenReturn(testUser());
		LimitOrderCreateRequest request = buyRequest("0.1", "1000000");

		LimitOrderResponse response = service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(account.getReservedCash()).isEqualTo(100_050L);
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(response.status()).isEqualTo("PENDING");
		assertThat(response.orderType()).isEqualTo("LIMIT");
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getPracticeAttemptId()).isNull();
		assertThat(orderCaptor.getValue().getPracticeAttemptRunNumber()).isNull();
		verify(practiceOrderAttributionPort).lockForOrder(USER_ID, instrument, OrderType.LIMIT);
		verifyNoInteractions(portfolioSellService);
	}

	@Test
	void createLimitBuyForTutorialSampleLocksAttemptBeforeAccountAndStoresAttribution() {
		Instrument instrument = cryptoInstrument(5_000L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account();
		TutorialAccount tutorialAccount = tutorialAccount();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(practiceOrderAttributionPort.lockForOrder(USER_ID, instrument, OrderType.LIMIT))
			.thenReturn(Optional.of(new PracticeOrderAttributionDto(50L, 3L, new BigDecimal("1000000"))));
		when(accountService.getAccountForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(tutorialAccountService.getOrCreateForUpdate(USER_ID, Market.CRYPTO,
			NOW))
			.thenReturn(tutorialAccount);
		when(userQueryService.getUser(USER_ID)).thenReturn(testUser());

		service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, buyRequest("0.1", "1000000"));

		assertThat(tutorialAccount.getReservedCash()).isEqualTo(100_050L);
		assertThat(account.getReservedCash()).isZero();
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getPracticeAttemptId()).isEqualTo(50L);
		assertThat(orderCaptor.getValue().getPracticeAttemptRunNumber()).isEqualTo(3L);
		InOrder lockOrder = org.mockito.Mockito.inOrder(practiceOrderAttributionPort, accountService);
		lockOrder.verify(practiceOrderAttributionPort).lockForOrder(USER_ID, instrument, OrderType.LIMIT);
		lockOrder.verify(accountService)
			.getAccountForUpdate(USER_ID, Market.CRYPTO);
	}

	@Test
	void createLimitBuyForTutorialSampleThrowsTutorialInsufficientCashRegardlessOfRealAccountBalance() {
		Instrument instrument = cryptoInstrument(5_000L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account();
		account.addCash(100_000_000L);
		TutorialAccount tutorialAccount = tutorialAccount();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(practiceOrderAttributionPort.lockForOrder(USER_ID, instrument, OrderType.LIMIT))
			.thenReturn(Optional.empty());
		when(accountService.getAccountForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(tutorialAccountService.getOrCreateForUpdate(USER_ID, Market.CRYPTO,
			NOW))
			.thenReturn(tutorialAccount);
		LimitOrderCreateRequest request = buyRequest("1", "15000000");

		assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.TUTORIAL_INSUFFICIENT_CASH));

		assertThat(tutorialAccount.getReservedCash()).isZero();
		assertThat(account.getReservedCash()).isZero();
		verifyNoInteractions(userQueryService, orderRepository);
	}

	@Test
	void createLimitSellForTutorialSampleLocksAttemptBeforeHoldingAndStoresAttribution() {
		Instrument instrument = cryptoInstrument(5_000L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account();
		Holding holding = mock(Holding.class);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(practiceOrderAttributionPort.lockForOrder(USER_ID, instrument, OrderType.LIMIT))
			.thenReturn(Optional.of(new PracticeOrderAttributionDto(50L, 3L, new BigDecimal("1000000"))));
		when(accountService.getAccountFor(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, BigDecimal.ONE))
			.thenReturn(holding);
		when(userQueryService.getUser(USER_ID)).thenReturn(testUser());

		service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, sellRequest("1", "70000000"));

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getPracticeAttemptId()).isEqualTo(50L);
		assertThat(orderCaptor.getValue().getPracticeAttemptRunNumber()).isEqualTo(3L);
		InOrder lockOrder = org.mockito.Mockito.inOrder(practiceOrderAttributionPort, portfolioSellService);
		lockOrder.verify(practiceOrderAttributionPort).lockForOrder(USER_ID, instrument, OrderType.LIMIT);
		lockOrder.verify(portfolioSellService).getHoldingForUpdateOrThrow(account, instrument, BigDecimal.ONE);
	}

	@Test
	void createLimitOrderBuyThrowsInsufficientCashWhenAvailableCashBelowRequired() {
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(accountService.getAccountForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		LimitOrderCreateRequest request = buyRequest("1", "50000000000");

		assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CASH));

		assertThat(account.getReservedCash()).isZero();
		verifyNoInteractions(userQueryService, orderRepository);
	}

	@Test
	void createLimitOrderSellReservesQuantityAndCreatesPendingOrder() {
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account();
		Holding holding = mock(Holding.class);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(accountService.getAccountFor(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, new BigDecimal("1")))
			.thenReturn(holding);
		when(userQueryService.getUser(USER_ID)).thenReturn(testUser());
		LimitOrderCreateRequest request = sellRequest("1", "70000000");

		LimitOrderResponse response = service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		verify(holding).reserveQuantity(new BigDecimal("1"));
		assertThat(response.status()).isEqualTo("PENDING");
		assertThat(response.side()).isEqualTo("SELL");
		verify(orderRepository).save(any(Order.class));
		verify(accountService, never())
			.getAccountForUpdate(any(), any());
	}

	@Test
	void createLimitOrderSellThrowsInsufficientQtyWhenHoldingLockThrows() {
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(accountService.getAccountFor(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, new BigDecimal("1")))
			.thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_QTY));
		LimitOrderCreateRequest request = sellRequest("1", "70000000");

		assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_QTY));

		verifyNoInteractions(userQueryService, orderRepository);
	}

	@Test
	void createLimitOrderCreatesPendingOrderEvenWhenBuyLimitPriceAlreadyMeetsImmediateFillCondition() {
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account();
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		when(accountService.getAccountForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(userQueryService.getUser(USER_ID)).thenReturn(testUser());
		LimitOrderCreateRequest request = buyRequest("0.001", "9000000");

		LimitOrderResponse response = service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request);

		assertThat(response.status()).isEqualTo("PENDING");
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getStatus().name()).isEqualTo("PENDING");
	}

	@Test
	void createLimitOrderThrowsValidationErrorWhenMarketIsStock() {
		LimitOrderCreateRequest request = new LimitOrderCreateRequest(
			Market.STOCK, 1L, OrderSide.BUY, new BigDecimal("1"), new BigDecimal("70000000"));

		assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(instrumentService, accountService, userQueryService, orderRepository);
	}

	@Test
	void createLimitOrderThrowsValidationErrorWhenInstrumentMarketDoesNotMatchRequestMarket() {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 1L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		LimitOrderCreateRequest request = buyRequest("1", "70000000");

		assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(accountService, userQueryService, orderRepository);
	}

	@Test
	void createLimitOrderThrowsValidationErrorWhenQuantityIsZero() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		LimitOrderCreateRequest request = buyRequest("0", "70000000");

		assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void createLimitOrderThrowsValidationErrorWhenQuantityExceedsEightDecimals() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		LimitOrderCreateRequest request = buyRequest("0.123456789", "70000000");

		assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void createLimitOrderThrowsValidationErrorWhenLimitPriceIsZero() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		LimitOrderCreateRequest request = buyRequest("1", "0");

		assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void createLimitOrderThrowsValidationErrorWhenOrderAmountBelowMinimum() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(instrument.getId())).thenReturn(instrument);
		LimitOrderCreateRequest request = buyRequest("0.0001", "1000");

		assertThatThrownBy(() -> service.execute(USER_ID, IDEMPOTENCY_KEY, REQUEST_HASH, request))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(accountService, userQueryService, orderRepository);
	}

	private static LimitOrderCreateRequest buyRequest(String quantity, String limitPrice) {
		return new LimitOrderCreateRequest(
			Market.CRYPTO, 1L, OrderSide.BUY, new BigDecimal(quantity), new BigDecimal(limitPrice));
	}

	private static LimitOrderCreateRequest sellRequest(String quantity, String limitPrice) {
		return new LimitOrderCreateRequest(
			Market.CRYPTO, 1L, OrderSide.SELL, new BigDecimal(quantity), new BigDecimal(limitPrice));
	}

	private static Instrument cryptoInstrument(long minOrderAmount) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), minOrderAmount, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 1L);
		return instrument;
	}

	private static Account account() {
		return Account.create(testUser(), Market.CRYPTO, NOW);
	}

	private static TutorialAccount tutorialAccount() {
		return TutorialAccount.create(testUser(), Market.CRYPTO, NOW);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}
}
