// 즐겨찾기 동시 등록, 빈 행 갭 락 회피와 기존 행 비관 잠금 직렬화를 실제 MySQL에서 검증한다.
package com.finplay.api.favorite.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.favorite.domain.Favorite;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.favorite.repository.FavoriteRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FavoriteConcurrencyIntegrationTest {

	@Autowired
	private FavoriteService favoriteService;
	@Autowired
	private FavoriteRepository favoriteRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private TransactionTemplate transactionTemplate;
	private Long createdUserId;

	@AfterEach
	void cleanUpCreatedUser() {
		if (createdUserId == null) {
			return;
		}
		jdbcTemplate.update("DELETE FROM favorites WHERE user_id = ?", createdUserId);
		userRepository.deleteById(createdUserId);
	}

	@Test
	void concurrentCreateFavoritePersistsOneRowAndMapsLoserToConflict() throws Exception {
		LocalDateTime now = LocalDateTime.of(2026, 8, 3, 10, 0);
		User user = userRepository.saveAndFlush(User.create(
			"favorite-race-163@finplay.com", "hash", "favorite-race-163", now));
		createdUserId = user.getId();
		Instrument instrument = instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK).get(0);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		Callable<Object> request = () -> {
			ready.countDown();
			start.await();
			try {
				return favoriteService.createFavorite(user.getId(), instrument.getId());
			} catch (BusinessException exception) {
				return exception.getErrorCode();
			}
		};

		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<Object> first = executor.submit(request);
			Future<Object> second = executor.submit(request);
			ready.await();
			start.countDown();
			List<Object> results = List.of(first.get(), second.get());

			assertThat(results).filteredOn(FavoriteResponse.class::isInstance).hasSize(1);
			assertThat(results).filteredOn(ErrorCode.DUPLICATE_RESOURCE::equals).hasSize(1);
		} finally {
			executor.shutdownNow();
		}
		Long matchingFavoriteCount = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM favorites WHERE user_id = ? AND instrument_id = ?",
			Long.class, user.getId(), instrument.getId());
		assertThat(matchingFavoriteCount).isEqualTo(1L);
	}

	@Test
	void missingFavoriteCheckDoesNotGapLockConcurrentCreate() throws Exception {
		LocalDateTime now = LocalDateTime.of(2026, 8, 4, 9, 0);
		User user = userRepository.saveAndFlush(User.create(
			"favorite-gap-race-173@finplay.com", "hash", "favorite-gap-race-173", now));
		createdUserId = user.getId();
		Instrument instrument = instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK).get(0);
		CountDownLatch missingCheckFinished = new CountDownLatch(1);
		CountDownLatch releaseMissingTransaction = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<Boolean> missingLookup = executor.submit(() -> transactionTemplate.execute(status -> {
				boolean exists = favoriteService.lockFavoriteIfPresent(user.getId(), instrument.getId());
				missingCheckFinished.countDown();
				await(releaseMissingTransaction);
				return exists;
			}));
			assertThat(missingCheckFinished.await(5, TimeUnit.SECONDS)).isTrue();

			Future<FavoriteResponse> create = executor.submit(
				() -> favoriteService.createFavorite(user.getId(), instrument.getId()));

			assertThat(create.get(5, TimeUnit.SECONDS).instrumentId()).isEqualTo(instrument.getId());
			releaseMissingTransaction.countDown();
			assertThat(missingLookup.get(5, TimeUnit.SECONDS)).isFalse();
		} finally {
			releaseMissingTransaction.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void pessimisticWriteSerializesConcurrentLookupOfSameFavorite() throws Exception {
		LocalDateTime now = LocalDateTime.of(2026, 8, 4, 10, 0);
		User user = userRepository.saveAndFlush(User.create(
			"favorite-delete-race-172@finplay.com", "hash", "favorite-delete-race-172", now));
		createdUserId = user.getId();
		Instrument instrument = instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK).get(0);
		Favorite favorite = favoriteRepository.saveAndFlush(Favorite.create(user, instrument, now));
		CountDownLatch firstHasLock = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<Long> first = executor.submit(() -> transactionTemplate.execute(status -> {
				Long id = favoriteRepository.findByUserIdAndInstrumentIdForUpdate(
					user.getId(), instrument.getId()).orElseThrow().getId();
				firstHasLock.countDown();
				await(releaseFirst);
				return id;
			}));
			assertThat(firstHasLock.await(5, TimeUnit.SECONDS)).isTrue();
			Future<Long> second = executor.submit(() -> transactionTemplate.execute(status -> {
				secondStarted.countDown();
				return favoriteRepository.findByUserIdAndInstrumentIdForUpdate(
					user.getId(), instrument.getId()).orElseThrow().getId();
			}));
			assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);

			releaseFirst.countDown();

			assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(favorite.getId());
			assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(favorite.getId());
		} finally {
			releaseFirst.countDown();
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
