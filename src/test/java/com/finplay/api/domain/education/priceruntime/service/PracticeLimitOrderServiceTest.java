// PracticeLimitOrderService.createOrder의 세션 소유권·상태·종목 일치 검증과 order 공개 서비스 위임을 검증하는 단위 테스트다.
package com.finplay.api.domain.education.priceruntime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.dto.request.PracticeLimitOrderCreateRequest;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.service.PracticeLimitOrderCreationService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PracticeLimitOrderServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long OTHER_USER_ID = 2L;
	private static final Long SESSION_ID = 100L;
	private static final Long INSTRUMENT_ID = 10L;
	private static final Long OTHER_INSTRUMENT_ID = 20L;
	private static final long SEED = 12345L;
	private static final BigDecimal START_PRICE = new BigDecimal("10000.00000000");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 10, 0);

	private final PracticePriceSessionRepository practicePriceSessionRepository = mock(
		PracticePriceSessionRepository.class);
	private final PracticeLimitOrderCreationService practiceLimitOrderCreationService = mock(
		PracticeLimitOrderCreationService.class);

	private final PracticeLimitOrderService service = new PracticeLimitOrderService(practicePriceSessionRepository,
		practiceLimitOrderCreationService);

	@Test
	void createOrderLocksSessionOwnerScopeAndDelegatesToOrderCreationServiceWhenActiveAndInstrumentMatches() {
		PracticePriceSession session = activeSession();
		when(practicePriceSessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID))
			.thenReturn(Optional.of(session));
		LimitOrderResponse expected = sampleResponse();
		when(practiceLimitOrderCreationService.createSessionBuyOrder(
			USER_ID, SESSION_ID, INSTRUMENT_ID, new BigDecimal("0.1"), new BigDecimal("9500")))
			.thenReturn(expected);
		PracticeLimitOrderCreateRequest request = new PracticeLimitOrderCreateRequest(
			SESSION_ID, INSTRUMENT_ID, new BigDecimal("0.1"), new BigDecimal("9500"));

		LimitOrderResponse response = service.createOrder(USER_ID, request);

		assertThat(response).isSameAs(expected);
		verify(practicePriceSessionRepository).findByIdAndUserIdForUpdate(SESSION_ID, USER_ID);
		verify(practiceLimitOrderCreationService).createSessionBuyOrder(
			USER_ID, SESSION_ID, INSTRUMENT_ID, new BigDecimal("0.1"), new BigDecimal("9500"));
	}

	@Test
	void createOrderFailsWithNotFoundWhenSessionMissingOrOwnedByAnotherUser() {
		when(practicePriceSessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, OTHER_USER_ID))
			.thenReturn(Optional.empty());
		PracticeLimitOrderCreateRequest request = new PracticeLimitOrderCreateRequest(
			SESSION_ID, INSTRUMENT_ID, new BigDecimal("0.1"), new BigDecimal("9500"));

		assertThatThrownBy(() -> service.createOrder(OTHER_USER_ID, request))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

		verifyNoInteractions(practiceLimitOrderCreationService);
	}

	@Test
	void createOrderFailsWithSessionClosedWhenSessionIsCompleted() {
		PracticePriceSession session = completedSession();
		when(practicePriceSessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID))
			.thenReturn(Optional.of(session));
		PracticeLimitOrderCreateRequest request = new PracticeLimitOrderCreateRequest(
			SESSION_ID, INSTRUMENT_ID, new BigDecimal("0.1"), new BigDecimal("9500"));

		assertThatThrownBy(() -> service.createOrder(USER_ID, request))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.PRACTICE_PRICE_SESSION_CLOSED));

		verifyNoInteractions(practiceLimitOrderCreationService);
	}

	@Test
	void createOrderFailsWithSessionMismatchWhenRequestInstrumentDiffersFromSessionInstrument() {
		PracticePriceSession session = activeSession();
		when(practicePriceSessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID))
			.thenReturn(Optional.of(session));
		PracticeLimitOrderCreateRequest request = new PracticeLimitOrderCreateRequest(
			SESSION_ID, OTHER_INSTRUMENT_ID, new BigDecimal("0.1"), new BigDecimal("9500"));

		assertThatThrownBy(() -> service.createOrder(USER_ID, request))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.PRACTICE_PRICE_SESSION_MISMATCH));

		verifyNoInteractions(practiceLimitOrderCreationService);
	}

	private PracticePriceSession activeSession() {
		PracticePriceSession session = PracticePriceSession.create(
			USER_ID, INSTRUMENT_ID, SEED, (short)PracticePriceGeneratorV1.VERSION, START_PRICE, NOW);
		org.springframework.test.util.ReflectionTestUtils.setField(session, "id", SESSION_ID);
		return session;
	}

	private PracticePriceSession completedSession() {
		PracticePriceSession session = activeSession();
		BigDecimal lastPrice = PracticePriceGeneratorV1.nextPrice(SEED, 1, START_PRICE, START_PRICE);
		session.advance(1, lastPrice);
		// 테스트 목적상 tick 1에서 바로 완료 처리한다 — complete()는 ACTIVE 상태만 요구하고 tick 값을 검증하지 않는다.
		session.complete(NOW);
		return session;
	}

	private LimitOrderResponse sampleResponse() {
		return new LimitOrderResponse(
			55L, "CRYPTO", INSTRUMENT_ID, "BUY", "LIMIT", "PENDING",
			new BigDecimal("0.1"), new BigDecimal("9500"), NOW);
	}
}
