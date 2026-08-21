// 실습 의도 생성과 즐겨찾기 삭제가 같은 사용자 단위 인메모리 락(FavoriteService#withFavoriteLock)을 두고
// 실제로 직렬화되는지 순수 멀티스레드로 검증한다(#193: DB 비관 잠금 기반 통합 테스트에서 전환).
package com.finplay.api.domain.education.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.education.model.PracticeIntention;
import com.finplay.api.domain.education.entity.PracticeProgress;
import com.finplay.api.domain.education.entity.PracticeProgressStatus;
import com.finplay.api.domain.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.domain.education.repository.PracticeIntentionRepository;
import com.finplay.api.domain.education.repository.PracticeProgressRepository;
import com.finplay.api.domain.favorite.service.FavoriteService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PracticeIntentionFavoriteLockTest {

	private static final long USER_ID = 7L;
	private static final long INSTRUMENT_ID = 10L;
	private PracticeProgressRepository progressRepository;
	private PracticeIntentionRepository intentionRepository;
	private InstrumentService instrumentService;
	private FavoriteService favoriteService;
	private PracticeIntentionService intentionService;

	@BeforeEach
	void setUp() {
		progressRepository = mock(PracticeProgressRepository.class);
		intentionRepository = mock(PracticeIntentionRepository.class);
		instrumentService = mock(InstrumentService.class);
		favoriteService = new FavoriteService(instrumentService, Clock.systemUTC());
		intentionService = new PracticeIntentionService(
			progressRepository, intentionRepository, favoriteService, instrumentService, Clock.systemUTC());

		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", BigDecimal.ONE, 1L, true, LocalDateTime.now());
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		PracticeProgress inProgress = progress(PracticeProgressStatus.IN_PROGRESS);
		when(progressRepository.findByUserIdAndTutorialKeyForUpdate(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(inProgress));
	}

	@Test
	void deleteFinishingWhileIntentionWaitsMakesIntentionFailLockedWithoutSaving() throws Exception {
		favoriteService.createFavorite(USER_ID, INSTRUMENT_ID);
		CountDownLatch deleteHoldsLock = new CountDownLatch(1);
		CountDownLatch releaseDelete = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			// delete 스레드가 사용자 락을 쥔 채 유지하도록, 락 내부에서 실제 삭제를 수행한 뒤 대기시킨다.
			Future<Void> delete = executor.submit(() -> {
				favoriteService.withFavoriteLock(USER_ID, INSTRUMENT_ID, () -> {
					favoriteService.deleteFavorite(USER_ID, INSTRUMENT_ID);
					deleteHoldsLock.countDown();
					await(releaseDelete);
					return null;
				});
				return null;
			});
			assertThat(deleteHoldsLock.await(5, TimeUnit.SECONDS)).isTrue();

			Future<Object> intention = executor.submit(this::createIntentionCapturingErrorCode);
			// intention은 delete가 같은 사용자 락을 쥐고 있는 동안 완료될 수 없어야 한다.
			assertThatThrownBy(() -> intention.get(300, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);

			releaseDelete.countDown();
			delete.get(5, TimeUnit.SECONDS);
			assertThat(intention.get(5, TimeUnit.SECONDS)).isEqualTo(ErrorCode.PRACTICE_STEP_LOCKED);
		} finally {
			releaseDelete.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void intentionHoldingLockMakesDeleteWaitThenBothCompleteInOrder() throws Exception {
		favoriteService.createFavorite(USER_ID, INSTRUMENT_ID);
		CountDownLatch intentionHoldsLock = new CountDownLatch(1);
		CountDownLatch releaseIntention = new CountDownLatch(1);
		when(intentionRepository.save(any())).thenAnswer(invocation -> {
			intentionHoldsLock.countDown();
			await(releaseIntention);
			PracticeIntention withoutId = invocation.getArgument(0);
			return new PracticeIntention(99L, withoutId.userId(), withoutId.instrumentId(),
				withoutId.quantity(), withoutId.stopLoss(), withoutId.takeProfit(), withoutId.createdAt());
		});
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Object> intention = executor.submit(this::createIntentionCapturingErrorCode);
			assertThat(intentionHoldsLock.await(5, TimeUnit.SECONDS)).isTrue();

			Future<Void> delete = executor.submit(() -> {
				favoriteService.deleteFavorite(USER_ID, INSTRUMENT_ID);
				return null;
			});
			// delete는 intention이 같은 사용자 락을 쥐고 있는 동안 완료될 수 없어야 한다.
			assertThatThrownBy(() -> delete.get(300, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);

			releaseIntention.countDown();
			assertThat(intention.get(5, TimeUnit.SECONDS)).isEqualTo(99L);
			delete.get(5, TimeUnit.SECONDS);
		} finally {
			releaseIntention.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(favoriteService.isFavorited(USER_ID, INSTRUMENT_ID)).isFalse();
	}

	private Object createIntentionCapturingErrorCode() {
		try {
			return intentionService.createIntention(USER_ID, request()).intentionId();
		} catch (BusinessException exception) {
			return exception.getErrorCode();
		}
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

	private void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}
}
