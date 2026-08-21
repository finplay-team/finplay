// 즐겨찾기 인메모리 저장소의 동시 등록과 사용자 단위 ReentrantLock 직렬화를 순수 멀티스레드로 검증한다(#193: DB 동시성에서 전환).
package com.finplay.api.domain.favorite.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.favorite.dto.response.FavoriteResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FavoriteConcurrencyTest {

	private final InstrumentService instrumentService = Mockito.mock(InstrumentService.class);
	private final FavoriteService favoriteService = new FavoriteService(instrumentService, Clock.systemUTC());

	@Test
	void concurrentCreateFavoritePersistsOneEntryAndMapsLoserToConflict() throws Exception {
		long userId = 7L;
		long instrumentId = 10L;
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", java.math.BigDecimal.ONE, 1L, true, java.time.LocalDateTime.now());
		Mockito.when(instrumentService.getInstrumentEntity(instrumentId)).thenReturn(instrument);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		Callable<Object> request = () -> {
			ready.countDown();
			start.await();
			try {
				return favoriteService.createFavorite(userId, instrumentId);
			} catch (BusinessException exception) {
				return exception.getErrorCode();
			}
		};

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Object> first = executor.submit(request);
			Future<Object> second = executor.submit(request);
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			List<Object> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

			assertThat(results).filteredOn(FavoriteResponse.class::isInstance).hasSize(1);
			assertThat(results).filteredOn(ErrorCode.DUPLICATE_RESOURCE::equals).hasSize(1);
		} finally {
			executor.shutdownNow();
		}
		assertThat(favoriteService.getFavorites(userId).content()).hasSize(1);
	}

	@Test
	void withFavoriteLockSerializesConcurrentActionsForSameUser() throws Exception {
		long userId = 7L;
		CountDownLatch firstHoldsLock = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<String> first = executor.submit(() -> favoriteService.withFavoriteLock(userId, 10L, () -> {
				firstHoldsLock.countDown();
				await(releaseFirst);
				return "first";
			}));
			assertThat(firstHoldsLock.await(5, TimeUnit.SECONDS)).isTrue();

			Future<String> second = executor.submit(() -> {
				secondStarted.countDown();
				return favoriteService.withFavoriteLock(userId, 20L, () -> "second");
			});
			assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
			// second는 first가 락을 쥐고 있는 동안 완료될 수 없어야 한다 — 사용자 단위 락이므로 instrumentId가
			// 달라도 직렬화된다.
			org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
				() -> second.get(300, TimeUnit.MILLISECONDS));

			releaseFirst.countDown();

			assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("first");
			assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("second");
		} finally {
			releaseFirst.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void withFavoriteLockDoesNotSerializeDifferentUsers() throws Exception {
		CountDownLatch userALocked = new CountDownLatch(1);
		CountDownLatch releaseUserA = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<String> userA = executor.submit(() -> favoriteService.withFavoriteLock(1L, 10L, () -> {
				userALocked.countDown();
				await(releaseUserA);
				return "a";
			}));
			assertThat(userALocked.await(5, TimeUnit.SECONDS)).isTrue();

			Future<String> userB = executor.submit(() -> favoriteService.withFavoriteLock(2L, 10L, () -> "b"));
			// 서로 다른 사용자이므로 userA가 락을 쥔 채여도 즉시 완료돼야 한다.
			assertThat(userB.get(5, TimeUnit.SECONDS)).isEqualTo("b");

			releaseUserA.countDown();
			assertThat(userA.get(5, TimeUnit.SECONDS)).isEqualTo("a");
		} finally {
			releaseUserA.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
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
