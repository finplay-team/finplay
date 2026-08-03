// 실습 의도 생성의 잠금 순서와 성공·실패 분기를 검증하는 단위 테스트다.
package com.finplay.api.education.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.domain.PracticeIntention;
import com.finplay.api.education.domain.PracticeProgress;
import com.finplay.api.education.domain.PracticeProgressStatus;
import com.finplay.api.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.education.repository.PracticeIntentionRepository;
import com.finplay.api.education.repository.PracticeProgressRepository;
import com.finplay.api.favorite.service.FavoriteService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.service.InstrumentService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PracticeIntentionServiceTest {

	private static final long USER_ID = 7L;
	private static final long INSTRUMENT_ID = 10L;
	private static final Instant NOW = Instant.parse("2026-08-04T01:00:00Z");
	private PracticeProgressRepository progressRepository;
	private PracticeIntentionRepository intentionRepository;
	private FavoriteService favoriteService;
	private UserQueryService userQueryService;
	private InstrumentService instrumentService;
	private PracticeIntentionService service;

	@BeforeEach
	void setUp() {
		progressRepository = mock(PracticeProgressRepository.class);
		intentionRepository = mock(PracticeIntentionRepository.class);
		favoriteService = mock(FavoriteService.class);
		userQueryService = mock(UserQueryService.class);
		instrumentService = mock(InstrumentService.class);
		service = new PracticeIntentionService(progressRepository, intentionRepository, favoriteService,
			userQueryService, instrumentService, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void createIntentionCreatesMultipleIntentionsAndLocksProgressBeforeFavorite() {
		User user = mock(User.class);
		Instrument instrument = mock(Instrument.class);
		when(instrument.getId()).thenReturn(INSTRUMENT_ID);
		PracticeProgress progress = progress(PracticeProgressStatus.IN_PROGRESS);
		when(userQueryService.getUser(USER_ID)).thenReturn(user);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(progressRepository.findByUserIdAndTutorialKeyForUpdate(USER_ID,
			PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));
		when(favoriteService.lockFavoriteIfPresent(USER_ID, INSTRUMENT_ID)).thenReturn(true);
		when(intentionRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
			PracticeIntention saved = invocation.getArgument(0);
			org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", 99L);
			return saved;
		});

		var first = service.createIntention(USER_ID, request());
		var second = service.createIntention(USER_ID, request());

		assertThat(first.intentionId()).isEqualTo(99L);
		assertThat(first.instrumentId()).isEqualTo(INSTRUMENT_ID);
		assertThat(first.quantity()).isEqualByComparingTo("2.50000000");
		assertThat(first.stopLoss()).isEqualByComparingTo("90.00000000");
		assertThat(first.takeProfit()).isEqualByComparingTo("120.00000000");
		assertThat(first.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 4, 1, 0));
		assertThat(second).isEqualTo(first);
		verify(intentionRepository, org.mockito.Mockito.times(2))
			.save(org.mockito.ArgumentMatchers.any(PracticeIntention.class));
		InOrder order = inOrder(progressRepository, favoriteService, intentionRepository);
		order.verify(progressRepository).insertIfAbsent(USER_ID, PracticeIntentionService.TUTORIAL_KEY,
			LocalDateTime.of(2026, 8, 4, 1, 0));
		order.verify(progressRepository).findByUserIdAndTutorialKeyForUpdate(USER_ID,
			PracticeIntentionService.TUTORIAL_KEY);
		order.verify(favoriteService).lockFavoriteIfPresent(USER_ID, INSTRUMENT_ID);
		order.verify(intentionRepository).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void createIntentionPropagatesMissingUserAndInstrument() {
		when(userQueryService.getUser(USER_ID)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
		assertError(ErrorCode.NOT_FOUND);
		verify(progressRepository, never()).insertIfAbsent(org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

		doReturn(mock(User.class)).when(userQueryService).getUser(USER_ID);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
		assertError(ErrorCode.NOT_FOUND);
	}

	@Test
	void createIntentionFailsWhenFavoriteStepIsLockedWithoutSaving() {
		stubDependencies(PracticeProgressStatus.IN_PROGRESS);
		when(favoriteService.lockFavoriteIfPresent(USER_ID, INSTRUMENT_ID)).thenReturn(false);

		assertError(ErrorCode.PRACTICE_STEP_LOCKED);

		verify(intentionRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void createIntentionFailsWithInternalErrorWhenProgressRowIsUnexpectedlyMissingAfterInsert() {
		User user = mock(User.class);
		Instrument instrument = mock(Instrument.class);
		when(userQueryService.getUser(USER_ID)).thenReturn(user);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(progressRepository.findByUserIdAndTutorialKeyForUpdate(USER_ID,
			PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.empty());

		assertError(ErrorCode.INTERNAL_ERROR);

		verify(favoriteService, never()).lockFavoriteIfPresent(USER_ID, INSTRUMENT_ID);
		verify(intentionRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void createIntentionFailsWhenPracticeAlreadyCompletedWithoutLockingFavoriteOrSaving() {
		stubDependencies(PracticeProgressStatus.COMPLETED);

		assertError(ErrorCode.PRACTICE_ALREADY_COMPLETED);

		verify(favoriteService, never()).lockFavoriteIfPresent(USER_ID, INSTRUMENT_ID);
		verify(intentionRepository, never()).save(org.mockito.ArgumentMatchers.any());
	}

	private void stubDependencies(PracticeProgressStatus status) {
		User user = mock(User.class);
		Instrument instrument = mock(Instrument.class);
		PracticeProgress progress = progress(status);
		when(userQueryService.getUser(USER_ID)).thenReturn(user);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(progressRepository.findByUserIdAndTutorialKeyForUpdate(USER_ID,
			PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));
	}

	private PracticeProgress progress(PracticeProgressStatus status) {
		PracticeProgress progress = mock(PracticeProgress.class);
		when(progress.getStatus()).thenReturn(status);
		return progress;
	}

	private PracticeIntentionCreateRequest request() {
		return new PracticeIntentionCreateRequest(INSTRUMENT_ID, new BigDecimal("2.50000000"),
			new BigDecimal("90.00000000"), new BigDecimal("120.00000000"));
	}

	private void assertError(ErrorCode errorCode) {
		assertThatThrownBy(() -> service.createIntention(USER_ID, request()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(errorCode));
	}
}
