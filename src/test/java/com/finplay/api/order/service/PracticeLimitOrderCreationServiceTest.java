// PracticeLimitOrderCreationService.createSessionBuyOrder의 BUY 고정·세션 PENDING 1건 상한·검증 재사용을 검증하는 단위 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.dto.response.LimitOrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeLimitOrderCreationServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long SESSION_ID = 100L;
	private static final Long INSTRUMENT_ID = 10L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-11T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final UserQueryService userQueryService = mock(UserQueryService.class);
	private final AccountService accountService = mock(AccountService.class);
	private final InstrumentService instrumentService = mock(InstrumentService.class);
	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final PracticeOrderAttributionPort practiceOrderAttributionPort = mock(
		PracticeOrderAttributionPort.class);
	private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

	private final PracticeLimitOrderCreationService service = new PracticeLimitOrderCreationService(
		userQueryService, accountService, instrumentService, orderRepository, practiceOrderAttributionPort, clock);

	@Test
	void createSessionBuyOrderReservesCashAndCreatesPendingBuyOrderWithSessionId() {
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account();
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(orderRepository.existsByPracticePriceSessionIdAndStatus(SESSION_ID, OrderStatus.PENDING))
			.thenReturn(false);
		when(accountService.getAccountForUpdate(USER_ID, com.finplay.api.account.domain.Market.CRYPTO))
			.thenReturn(account);
		when(userQueryService.getUser(USER_ID)).thenReturn(testUser());

		LimitOrderResponse response = service.createSessionBuyOrder(
			USER_ID, SESSION_ID, INSTRUMENT_ID, new BigDecimal("0.1"), new BigDecimal("1000000"));

		// amount = 0.1 * 1,000,000 = 100,000, fee = floor(100,000*0.0005) = 50
		assertThat(account.getReservedCash()).isEqualTo(100_050L);
		assertThat(response.side()).isEqualTo("BUY");
		assertThat(response.status()).isEqualTo("PENDING");
		assertThat(response.orderType()).isEqualTo("LIMIT");
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getPracticePriceSessionId()).isEqualTo(SESSION_ID);
		assertThat(orderCaptor.getValue().getSide().name()).isEqualTo("BUY");
		assertThat(orderCaptor.getValue().getPracticeAttemptId()).isNull();
		assertThat(orderCaptor.getValue().getPracticeAttemptRunNumber()).isNull();
	}

	@Test
	void createSessionBuyOrderStoresAttemptAttributionForTutorialSample() {
		Instrument instrument = cryptoInstrument(5_000L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account();
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(practiceOrderAttributionPort.lockForOrder(USER_ID, instrument))
			.thenReturn(Optional.of(new PracticeOrderAttributionDto(50L, 3L)));
		when(orderRepository.existsByPracticePriceSessionIdAndStatus(SESSION_ID, OrderStatus.PENDING))
			.thenReturn(false);
		when(accountService.getAccountForUpdate(USER_ID, com.finplay.api.account.domain.Market.CRYPTO))
			.thenReturn(account);
		when(userQueryService.getUser(USER_ID)).thenReturn(testUser());

		service.createSessionBuyOrder(
			USER_ID, SESSION_ID, INSTRUMENT_ID, new BigDecimal("0.1"), new BigDecimal("1000000"));

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository).save(orderCaptor.capture());
		assertThat(orderCaptor.getValue().getPracticePriceSessionId()).isEqualTo(SESSION_ID);
		assertThat(orderCaptor.getValue().getPracticeAttemptId()).isEqualTo(50L);
		assertThat(orderCaptor.getValue().getPracticeAttemptRunNumber()).isEqualTo(3L);
	}

	@Test
	void createSessionBuyOrderThrowsAlreadyPendingWhenSessionHasPendingOrder() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(orderRepository.existsByPracticePriceSessionIdAndStatus(SESSION_ID, OrderStatus.PENDING))
			.thenReturn(true);

		assertThatThrownBy(() -> service.createSessionBuyOrder(
			USER_ID, SESSION_ID, INSTRUMENT_ID, new BigDecimal("0.1"), new BigDecimal("1000000")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.PRACTICE_LIMIT_ORDER_ALREADY_PENDING));

		verifyNoInteractions(accountService, userQueryService);
		verify(orderRepository, org.mockito.Mockito.never()).save(any());
	}

	@Test
	void createSessionBuyOrderThrowsInsufficientCashWhenAvailableCashBelowRequired() {
		Instrument instrument = cryptoInstrument(5_000L);
		Account account = account();
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(orderRepository.existsByPracticePriceSessionIdAndStatus(SESSION_ID, OrderStatus.PENDING))
			.thenReturn(false);
		when(accountService.getAccountForUpdate(USER_ID, com.finplay.api.account.domain.Market.CRYPTO))
			.thenReturn(account);

		assertThatThrownBy(() -> service.createSessionBuyOrder(
			USER_ID, SESSION_ID, INSTRUMENT_ID, new BigDecimal("1"), new BigDecimal("50000000000")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CASH));

		assertThat(account.getReservedCash()).isZero();
		verifyNoInteractions(userQueryService);
		verify(orderRepository, org.mockito.Mockito.never()).save(any());
	}

	@Test
	void createSessionBuyOrderThrowsInstrumentNotTradableWhenMarketIsStock() {
		Instrument stock = Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, NOW);
		ReflectionTestUtils.setField(stock, "id", INSTRUMENT_ID);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(stock);

		assertThatThrownBy(() -> service.createSessionBuyOrder(
			USER_ID, SESSION_ID, INSTRUMENT_ID, new BigDecimal("1"), new BigDecimal("70000000")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSTRUMENT_NOT_TRADABLE));

		verifyNoInteractions(accountService, userQueryService, orderRepository);
	}

	@Test
	void createSessionBuyOrderThrowsInstrumentNotTradableWhenCryptoIsNotTradable() {
		Instrument instrument = cryptoInstrument(5_000L);
		ReflectionTestUtils.setField(instrument, "tradable", false);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);

		assertThatThrownBy(() -> service.createSessionBuyOrder(
			USER_ID, SESSION_ID, INSTRUMENT_ID, new BigDecimal("1"), new BigDecimal("70000000")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSTRUMENT_NOT_TRADABLE));

		verifyNoInteractions(accountService, userQueryService, orderRepository);
	}

	@Test
	void createSessionBuyOrderThrowsValidationErrorWhenQuantityIsZero() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);

		assertThatThrownBy(() -> service.createSessionBuyOrder(
			USER_ID, SESSION_ID, INSTRUMENT_ID, BigDecimal.ZERO, new BigDecimal("70000000")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(accountService, userQueryService, orderRepository);
	}

	@Test
	void createSessionBuyOrderThrowsValidationErrorWhenOrderAmountBelowMinimum() {
		Instrument instrument = cryptoInstrument(5_000L);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		// rawAmount = 0.0001 * 1000 = 0.1 < 5000 최소 주문금액
		assertThatThrownBy(() -> service.createSessionBuyOrder(
			USER_ID, SESSION_ID, INSTRUMENT_ID, new BigDecimal("0.0001"), new BigDecimal("1000")))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));

		verifyNoInteractions(accountService, userQueryService, orderRepository);
	}

	private static Instrument cryptoInstrument(long minOrderAmount) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), minOrderAmount, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		return instrument;
	}

	private static Account account() {
		return Account.create(testUser(), com.finplay.api.account.domain.Market.CRYPTO, NOW);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}
}
