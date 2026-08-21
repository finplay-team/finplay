// 실습 의도 생성의 잠금 순서(progress DB → favorite in-memory)와 성공·실패 분기를 검증하는 단위 테스트다.
package com.finplay.api.domain.education.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.domain.education.entity.PracticeProgress;
import com.finplay.api.domain.education.entity.PracticeProgressStatus;
import com.finplay.api.domain.education.model.PracticeIntention;
import com.finplay.api.domain.education.repository.PracticeIntentionRepository;
import com.finplay.api.domain.education.repository.PracticeProgressRepository;
import com.finplay.api.domain.favorite.service.FavoriteService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;
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
	private InstrumentService instrumentService;
	private PracticeIntentionService service;

	@BeforeEach
	void setUp() {
		progressRepository = mock(PracticeProgressRepository.class);
		intentionRepository = mock(PracticeIntentionRepository.class);
		favoriteService = mock(FavoriteService.class);
		instrumentService = mock(InstrumentService.class);
		service = new PracticeIntentionService(
			progressRepository, intentionRepository, favoriteService, instrumentService,
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void createIntentionLocksProgressBeforeFavoriteAndSavesWhenFavorited() {
		stubWithFavoriteLockInvokesAction();
		Instrument instrument = mock(Instrument.class);
		when(instrument.getMarket()).thenReturn(Market.STOCK);
		PracticeProgress progress = progress(PracticeProgressStatus.IN_PROGRESS);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(progressRepository.findByUserIdAndTutorialKeyForUpdate(USER_ID,
			PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));
		when(favoriteService.isFavorited(USER_ID, INSTRUMENT_ID)).thenReturn(true);
		when(intentionRepository.save(any())).thenAnswer(invocation -> {
			PracticeIntention withoutId = invocation.getArgument(0);
			return new PracticeIntention(99L, withoutId.userId(), withoutId.instrumentId(),
				withoutId.quantity(), withoutId.stopLoss(), withoutId.takeProfit(), withoutId.createdAt());
		});

		var result = service.createIntention(USER_ID, request());

		assertThat(result.intentionId()).isEqualTo(99L);
		assertThat(result.instrumentId()).isEqualTo(INSTRUMENT_ID);
		assertThat(result.quantity()).isEqualByComparingTo("2.50000000");
		assertThat(result.stopLoss()).isEqualByComparingTo("90.00000000");
		assertThat(result.takeProfit()).isEqualByComparingTo("120.00000000");
		assertThat(result.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 4, 1, 0));
		verify(intentionRepository).save(any(PracticeIntention.class));
		InOrder order = inOrder(instrumentService, progressRepository, favoriteService);
		order.verify(instrumentService).getInstrumentEntity(INSTRUMENT_ID);
		order.verify(progressRepository).insertIfAbsent(USER_ID, PracticeIntentionService.TUTORIAL_KEY,
			LocalDateTime.of(2026, 8, 4, 1, 0));
		order.verify(progressRepository).findByUserIdAndTutorialKeyForUpdate(USER_ID,
			PracticeIntentionService.TUTORIAL_KEY);
		order.verify(favoriteService).withFavoriteLock(anyLong(), anyLong(), any());
	}

	@Test
	void createIntentionPropagatesMissingInstrumentBeforeTouchingProgress() {
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertError(ErrorCode.NOT_FOUND);

		verify(progressRepository, never()).insertIfAbsent(any(), any(), any());
		verify(favoriteService, never()).withFavoriteLock(anyLong(), anyLong(), any());
	}

	@Test
	void createIntentionFailsWhenFavoriteStepIsLockedWithoutSaving() {
		stubWithFavoriteLockInvokesAction();
		stubDependencies(PracticeProgressStatus.IN_PROGRESS);
		when(favoriteService.isFavorited(USER_ID, INSTRUMENT_ID)).thenReturn(false);

		assertError(ErrorCode.PRACTICE_STEP_LOCKED);

		verify(intentionRepository, never()).save(any());
	}

	@Test
	void createIntentionFailsWithInternalErrorWhenProgressRowIsUnexpectedlyMissingAfterInsert() {
		Instrument instrument = mock(Instrument.class);
		when(instrument.getMarket()).thenReturn(Market.STOCK);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(progressRepository.findByUserIdAndTutorialKeyForUpdate(USER_ID,
			PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.empty());

		assertError(ErrorCode.INTERNAL_ERROR);

		verify(favoriteService, never()).withFavoriteLock(anyLong(), anyLong(), any());
		verify(intentionRepository, never()).save(any());
	}

	@Test
	void createIntentionFailsWhenPracticeAlreadyCompletedWithoutLockingFavoriteOrSaving() {
		stubDependencies(PracticeProgressStatus.COMPLETED);

		assertError(ErrorCode.PRACTICE_ALREADY_COMPLETED);

		verify(favoriteService, never()).withFavoriteLock(anyLong(), anyLong(), any());
		verify(intentionRepository, never()).save(any());
	}

	@Test
	void createIntentionForCryptoInstrumentUsesCoinTutorialKeyAndSavesWhenFavorited() {
		stubWithFavoriteLockInvokesAction();
		Instrument instrument = mock(Instrument.class);
		when(instrument.getMarket()).thenReturn(Market.CRYPTO);
		PracticeProgress progress = progress(PracticeProgressStatus.IN_PROGRESS);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(progressRepository.findByUserIdAndTutorialKeyForUpdate(USER_ID,
			PracticeIntentionService.COIN_TUTORIAL_KEY)).thenReturn(Optional.of(progress));
		when(favoriteService.isFavorited(USER_ID, INSTRUMENT_ID)).thenReturn(true);
		when(intentionRepository.save(any())).thenAnswer(invocation -> {
			PracticeIntention withoutId = invocation.getArgument(0);
			return new PracticeIntention(99L, withoutId.userId(), withoutId.instrumentId(),
				withoutId.quantity(), withoutId.stopLoss(), withoutId.takeProfit(), withoutId.createdAt());
		});

		var result = service.createIntention(USER_ID, request());

		assertThat(result.intentionId()).isEqualTo(99L);
		verify(intentionRepository).save(any(PracticeIntention.class));
		verify(progressRepository).insertIfAbsent(USER_ID, PracticeIntentionService.COIN_TUTORIAL_KEY,
			LocalDateTime.of(2026, 8, 4, 1, 0));
		verify(progressRepository, never()).insertIfAbsent(eq(USER_ID), eq(PracticeIntentionService.TUTORIAL_KEY),
			any());
	}

	@Test
	void createIntentionFailsWhenCoinFavoriteStepIsLockedWithoutSaving() {
		stubWithFavoriteLockInvokesAction();
		stubDependencies(PracticeProgressStatus.IN_PROGRESS, Market.CRYPTO, PracticeIntentionService.COIN_TUTORIAL_KEY);
		when(favoriteService.isFavorited(USER_ID, INSTRUMENT_ID)).thenReturn(false);

		assertError(ErrorCode.PRACTICE_STEP_LOCKED);

		verify(intentionRepository, never()).save(any());
	}

	@Test
	void createIntentionFailsWhenCoinPracticeAlreadyCompletedWithoutLockingFavoriteOrSaving() {
		stubDependencies(PracticeProgressStatus.COMPLETED, Market.CRYPTO, PracticeIntentionService.COIN_TUTORIAL_KEY);

		assertError(ErrorCode.PRACTICE_ALREADY_COMPLETED);

		verify(favoriteService, never()).withFavoriteLock(anyLong(), anyLong(), any());
		verify(intentionRepository, never()).save(any());
	}

	@SuppressWarnings("unchecked")
	private void stubWithFavoriteLockInvokesAction() {
		when(favoriteService.withFavoriteLock(anyLong(), anyLong(), any())).thenAnswer(
			invocation -> ((Supplier<Object>)invocation.getArgument(2)).get());
	}

	private void stubDependencies(PracticeProgressStatus status) {
		stubDependencies(status, Market.STOCK, PracticeIntentionService.TUTORIAL_KEY);
	}

	private void stubDependencies(PracticeProgressStatus status, Market market, String tutorialKey) {
		Instrument instrument = mock(Instrument.class);
		when(instrument.getMarket()).thenReturn(market);
		PracticeProgress progress = progress(status);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(progressRepository.findByUserIdAndTutorialKeyForUpdate(USER_ID, tutorialKey))
			.thenReturn(Optional.of(progress));
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
