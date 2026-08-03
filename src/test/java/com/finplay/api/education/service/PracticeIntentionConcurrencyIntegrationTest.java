// 실습 의도 최초 생성과 즐겨찾기 삭제 경합의 실제 트랜잭션 직렬화를 검증한다.
package com.finplay.api.education.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.domain.PracticeIntention;
import com.finplay.api.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.education.dto.response.PracticeIntentionResponse;
import com.finplay.api.education.repository.PracticeIntentionRepository;
import com.finplay.api.education.repository.PracticeProgressRepository;
import com.finplay.api.favorite.domain.Favorite;
import com.finplay.api.favorite.repository.FavoriteRepository;
import com.finplay.api.favorite.service.FavoriteService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PracticeIntentionConcurrencyIntegrationTest {

	@Autowired
	private PracticeIntentionService intentionService;
	@Autowired
	private FavoriteService favoriteService;
	@Autowired
	private PracticeProgressRepository progressRepository;
	@Autowired
	private PracticeIntentionRepository intentionRepository;
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
	private User user;
	private Instrument instrument;

	@BeforeEach
	void setUp() {
		LocalDateTime now = LocalDateTime.of(2026, 8, 4, 10, 0);
		user = userRepository.saveAndFlush(User.create(
			"practice-race-175@finplay.com", "hash", "practice-race-175", now));
		instrument = instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK).get(0);
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM practice_intentions WHERE user_id = ?", user.getId());
		jdbcTemplate.update("DELETE FROM practice_progresses WHERE user_id = ?", user.getId());
		jdbcTemplate.update("DELETE FROM favorites WHERE user_id = ?", user.getId());
		userRepository.deleteById(user.getId());
	}

	@Test
	void simultaneousFirstIntentionsConvergeToOneProgressAndTwoIntentionsWithoutUniqueException() throws Exception {
		favoriteRepository.saveAndFlush(Favorite.create(user, instrument, LocalDateTime.now()));
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		Callable<PracticeIntentionResponse> request = () -> {
			ready.countDown();
			start.await();
			return intentionService.createIntention(user.getId(), request());
		};
		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<PracticeIntentionResponse> first = executor.submit(request);
			Future<PracticeIntentionResponse> second = executor.submit(request);
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.extracting(PracticeIntentionResponse::intentionId).doesNotHaveDuplicates();
		} finally {
			start.countDown();
			shutdownAndAwait(executor);
		}
		assertThat(count("practice_progresses")).isEqualTo(1L);
		assertThat(count("practice_intentions")).isEqualTo(2L);
	}

	@Test
	void completedProgressIsNotOverwrittenAndCreatesNoIntention() {
		jdbcTemplate.update("""
			INSERT INTO practice_progresses (user_id, tutorial_key, status, started_at, completed_at)
			VALUES (?, ?, 'COMPLETED', NOW(6), NOW(6))
			""", user.getId(), PracticeIntentionService.TUTORIAL_KEY);

		assertThatThrownBy(() -> intentionService.createIntention(user.getId(), request()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_ALREADY_COMPLETED));
		assertThat(jdbcTemplate.queryForObject("""
			SELECT status FROM practice_progresses WHERE user_id = ? AND tutorial_key = ?
			""", String.class, user.getId(), PracticeIntentionService.TUTORIAL_KEY)).isEqualTo("COMPLETED");
		assertThat(count("practice_intentions")).isZero();
	}

	@Test
	void deleteFirstMakesIntentionWaitThenFailLockedWithoutPersistence() throws Exception {
		favoriteRepository.saveAndFlush(Favorite.create(user, instrument, LocalDateTime.now()));
		CountDownLatch deletedWhileLocked = new CountDownLatch(1);
		CountDownLatch releaseDelete = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<Void> delete = executor.submit(() -> transactionTemplate.execute(status -> {
				Favorite locked = favoriteRepository.findByUserIdAndInstrumentIdForUpdate(
					user.getId(), instrument.getId()).orElseThrow();
				favoriteRepository.delete(locked);
				favoriteRepository.flush();
				deletedWhileLocked.countDown();
				await(releaseDelete);
				return null;
			}));
			assertThat(deletedWhileLocked.await(5, TimeUnit.SECONDS)).isTrue();
			Future<Object> intention = executor.submit(() -> captureIntentionResult());
			assertThatThrownBy(() -> intention.get(300, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);
			releaseDelete.countDown();
			delete.get(5, TimeUnit.SECONDS);
			assertThat(intention.get(5, TimeUnit.SECONDS)).isEqualTo(ErrorCode.PRACTICE_STEP_LOCKED);
		} finally {
			releaseDelete.countDown();
			shutdownAndAwait(executor);
		}
		assertThat(count("practice_intentions")).isZero();
	}

	@Test
	void intentionFirstMakesDeleteWaitThenBothCommitInOrder() throws Exception {
		Favorite favorite = favoriteRepository.saveAndFlush(Favorite.create(user, instrument, LocalDateTime.now()));
		CountDownLatch intentionPersistedWithFavoriteLock = new CountDownLatch(1);
		CountDownLatch releaseIntention = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<Long> intention = executor.submit(() -> transactionTemplate.execute(status -> {
				progressRepository.insertIfAbsent(user.getId(), PracticeIntentionService.TUTORIAL_KEY,
					LocalDateTime.now());
				progressRepository.findByUserIdAndTutorialKeyForUpdate(
					user.getId(), PracticeIntentionService.TUTORIAL_KEY).orElseThrow();
				favoriteRepository.findByUserIdAndInstrumentIdForUpdate(
					user.getId(), instrument.getId()).orElseThrow();
				Long id = intentionRepository.saveAndFlush(PracticeIntention.create(
					user, instrument, request().quantity(), request().stopLoss(), request().takeProfit(),
					LocalDateTime.now())).getId();
				intentionPersistedWithFavoriteLock.countDown();
				await(releaseIntention);
				return id;
			}));
			assertThat(intentionPersistedWithFavoriteLock.await(5, TimeUnit.SECONDS)).isTrue();
			Future<Void> delete = executor.submit(() -> {
				favoriteService.deleteFavorite(user.getId(), instrument.getId());
				return null;
			});
			assertThatThrownBy(() -> delete.get(300, TimeUnit.MILLISECONDS))
				.isInstanceOf(TimeoutException.class);
			releaseIntention.countDown();
			assertThat(intention.get(5, TimeUnit.SECONDS)).isPositive();
			delete.get(5, TimeUnit.SECONDS);
		} finally {
			releaseIntention.countDown();
			shutdownAndAwait(executor);
		}
		assertThat(count("practice_intentions")).isEqualTo(1L);
		assertThat(favoriteRepository.findById(favorite.getId())).isEmpty();
	}

	private Object captureIntentionResult() {
		try {
			return intentionService.createIntention(user.getId(), request());
		} catch (BusinessException exception) {
			return exception.getErrorCode();
		}
	}

	private long count(String table) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?",
			Long.class, user.getId());
	}

	private PracticeIntentionCreateRequest request() {
		return new PracticeIntentionCreateRequest(instrument.getId(), new BigDecimal("2.50000000"),
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

	private void shutdownAndAwait(ExecutorService executor) throws InterruptedException {
		executor.shutdownNow();
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}
}
