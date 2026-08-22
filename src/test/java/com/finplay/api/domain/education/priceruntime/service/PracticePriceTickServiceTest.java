// 코인 가상 가격 세션 next-tick 진행 서비스의 소유권·상태·tick 순서 검증을 mock으로 검증하는 단위 테스트다.
package com.finplay.api.domain.education.priceruntime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.education.priceruntime.dto.response.PracticePriceSessionResponse;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSessionStatus;
import com.finplay.api.domain.education.priceruntime.event.PracticePriceTickAdvancedEvent;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PracticePriceTickServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long INSTRUMENT_ID = 10L;
	private static final Long SESSION_ID = 100L;
	private static final long SEED = 12345L;
	private static final BigDecimal START_PRICE = new BigDecimal("10000.00000000");
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 10, 0);

	@Mock
	private PracticePriceSessionRepository practicePriceSessionRepository;

	@Mock
	private org.springframework.context.ApplicationEventPublisher eventPublisher;

	private Clock clock;
	private PracticePriceTickService service;

	@BeforeEach
	void setUp() {
		clock = Clock.fixed(NOW.atZone(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));
		service = new PracticePriceTickService(practicePriceSessionRepository, eventPublisher, clock);
	}

	@Test
	void advanceTickAdvancesSessionAndReturnsPriceRecalculatedByGeneratorWhenExpectedTickIsCurrentTickPlusOne() {
		PracticePriceSession session = newSession();
		when(practicePriceSessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID))
			.thenReturn(Optional.of(session));
		BigDecimal expectedPrice = PracticePriceGeneratorV1.nextPrice(SEED, 1, START_PRICE, START_PRICE);

		PracticePriceSessionResponse response = service.advanceTick(USER_ID, SESSION_ID, 1);

		assertThat(response.currentTick()).isEqualTo(1);
		assertThat(response.currentPrice()).isEqualByComparingTo(expectedPrice);
		assertThat(response.status()).isEqualTo(PracticePriceSessionStatus.ACTIVE);
		ArgumentCaptor<PracticePriceTickAdvancedEvent> eventCaptor = ArgumentCaptor
			.forClass(PracticePriceTickAdvancedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		PracticePriceTickAdvancedEvent publishedEvent = eventCaptor.getValue();
		assertThat(publishedEvent.sessionId()).isEqualTo(SESSION_ID);
		assertThat(publishedEvent.userId()).isEqualTo(USER_ID);
		assertThat(publishedEvent.instrumentId()).isEqualTo(INSTRUMENT_ID);
		assertThat(publishedEvent.tick()).isEqualTo(1);
		assertThat(publishedEvent.price()).isEqualByComparingTo(expectedPrice);
		assertThat(publishedEvent.lastTick()).isFalse();
	}

	@Test
	void advanceTickFailsWithNotFoundWhenSessionMissingOrOwnedByAnotherUser() {
		when(practicePriceSessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.advanceTick(USER_ID, SESSION_ID, 1))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void advanceTickFailsWithSessionClosedWhenSessionIsCompleted() throws Exception {
		PracticePriceSession session = newSession();
		setField(session, "currentTick", (short)98);
		BigDecimal lastPrice = PracticePriceGeneratorV1.nextPrice(SEED, 99, START_PRICE, START_PRICE);
		session.advance(99, lastPrice);
		session.complete(NOW);
		assertThat(session.getStatus()).isEqualTo(PracticePriceSessionStatus.COMPLETED);
		when(practicePriceSessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID))
			.thenReturn(Optional.of(session));

		assertThatThrownBy(() -> service.advanceTick(USER_ID, SESSION_ID, 1))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.PRACTICE_PRICE_SESSION_CLOSED));
	}

	@Test
	void advanceTickFailsWithTickConflictWhenExpectedTickDuplicatesCurrentTick() throws Exception {
		PracticePriceSession session = newSession();
		setField(session, "currentTick", (short)5);
		when(practicePriceSessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID))
			.thenReturn(Optional.of(session));

		assertThatThrownBy(() -> service.advanceTick(USER_ID, SESSION_ID, 5))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.PRACTICE_PRICE_TICK_CONFLICT));
	}

	@Test
	void advanceTickFailsWithTickConflictWhenExpectedTickSkipsAhead() throws Exception {
		PracticePriceSession session = newSession();
		setField(session, "currentTick", (short)5);
		when(practicePriceSessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID))
			.thenReturn(Optional.of(session));

		assertThatThrownBy(() -> service.advanceTick(USER_ID, SESSION_ID, 7))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.PRACTICE_PRICE_TICK_CONFLICT));
	}

	@Test
	void advanceTickFailsWithTickConflictWhenExpectedTickIsBehindCurrentTick() throws Exception {
		PracticePriceSession session = newSession();
		setField(session, "currentTick", (short)5);
		when(practicePriceSessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID))
			.thenReturn(Optional.of(session));

		assertThatThrownBy(() -> service.advanceTick(USER_ID, SESSION_ID, 4))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.PRACTICE_PRICE_TICK_CONFLICT));
	}

	@Test
	void advanceTickCompletesSessionAndSetsCompletedAtWhenReachingTickNinetyNine() throws Exception {
		PracticePriceSession session = newSession();
		setField(session, "currentTick", (short)98);
		BigDecimal previousPrice = START_PRICE;
		for (int tick = 1; tick <= 98; tick++) {
			previousPrice = PracticePriceGeneratorV1.nextPrice(SEED, tick, previousPrice, START_PRICE);
		}
		setField(session, "currentPrice", previousPrice);
		when(practicePriceSessionRepository.findByIdAndUserIdForUpdate(SESSION_ID, USER_ID))
			.thenReturn(Optional.of(session));
		BigDecimal expectedPrice = PracticePriceGeneratorV1.nextPrice(SEED, 99, previousPrice, START_PRICE);

		PracticePriceSessionResponse response = service.advanceTick(USER_ID, SESSION_ID, 99);

		assertThat(response.currentTick()).isEqualTo(99);
		assertThat(response.currentPrice()).isEqualByComparingTo(expectedPrice);
		assertThat(response.status()).isEqualTo(PracticePriceSessionStatus.COMPLETED);
		assertThat(response.completedAt()).isEqualTo(LocalDateTime.now(clock));
		// lastTick=true 이벤트가 먼저 발행되고(같은 트랜잭션에서 리스너가 동기 체결·취소를 끝낸 뒤) 세션이
		// COMPLETED로 전이한다 — 이 테스트는 mock이라 리스너가 실제로 붙지 않으므로 이벤트 페이로드만 검증한다.
		ArgumentCaptor<PracticePriceTickAdvancedEvent> eventCaptor = ArgumentCaptor
			.forClass(PracticePriceTickAdvancedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		PracticePriceTickAdvancedEvent publishedEvent = eventCaptor.getValue();
		assertThat(publishedEvent.sessionId()).isEqualTo(SESSION_ID);
		assertThat(publishedEvent.tick()).isEqualTo(99);
		assertThat(publishedEvent.price()).isEqualByComparingTo(expectedPrice);
		assertThat(publishedEvent.lastTick()).isTrue();
	}

	private PracticePriceSession newSession() {
		PracticePriceSession session = PracticePriceSession.create(
			USER_ID, INSTRUMENT_ID, SEED, (short)PracticePriceGeneratorV1.VERSION, START_PRICE, NOW);
		try {
			setField(session, "id", SESSION_ID);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		return session;
	}

	private static void setField(Object target, String fieldName, Object value) throws Exception {
		var field = PracticePriceSession.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
