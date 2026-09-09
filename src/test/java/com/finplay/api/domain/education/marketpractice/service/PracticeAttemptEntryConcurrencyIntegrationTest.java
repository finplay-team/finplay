package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class PracticeAttemptEntryConcurrencyIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 21, 10, 0, 0);
	private static final int ROUNDS = 10;
	private static final int CONCURRENCY = 2;

	@Autowired
	private PracticeAttemptService practiceAttemptService;
	@Autowired
	private PracticeAttemptDeadlockRetryService practiceAttemptDeadlockRetryService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private TestClock clock;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final List<Long> createdUserIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@AfterEach
	void cleanUp() {
		for (Long userId : createdUserIds) {
			jdbcTemplate.update("DELETE FROM practice_attempts WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM practice_progresses WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM tutorial_accounts WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
		}
		createdUserIds.clear();
	}

	@Test
	void concurrentEnsureAttemptOnExistingRowAllSucceed() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			Long userId = createUser("entry-race-existing-" + round);
			practiceAttemptService.ensureAttempt(userId, Market.CRYPTO);

			List<Throwable> failures = runConcurrently(userId);

			assertThat(failures).as("round %d — 기존 행 동시 진입", round).isEmpty();
			assertThat(attemptRowCount(userId)).isEqualTo(1L);
		}
	}

	@Test
	void concurrentFirstEnsureAttemptAllSucceedAndCreateExactlyOneRow() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			Long userId = createUser("entry-race-first-" + round);

			List<Throwable> failures = runConcurrently(userId);

			assertThat(failures).as("round %d — 최초 동시 진입", round).isEmpty();
			assertThat(attemptRowCount(userId)).isEqualTo(1L);
			assertThat(tutorialAccountRowCount(userId)).isEqualTo(1L);
		}
	}

	@Test
	void concurrentEntryThroughProductionWiringAllSucceed() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			Long userId = createUser("entry-race-wiring-" + round);

			List<Throwable> failures = runConcurrently(
				userId, () -> practiceAttemptDeadlockRetryService.ensureAttempt(userId, Market.CRYPTO));

			assertThat(failures).as("round %d — 운영 배선 동시 진입", round).isEmpty();
			assertThat(attemptRowCount(userId)).isEqualTo(1L);
			assertThat(tutorialAccountRowCount(userId)).isEqualTo(1L);
		}
	}

	private List<Throwable> runConcurrently(Long userId) throws Exception {
		return runConcurrently(userId, () -> practiceAttemptService.ensureAttempt(userId, Market.CRYPTO));
	}

	private List<Throwable> runConcurrently(Long userId, Callable<PracticeAttemptResponse> call) throws Exception {
		CountDownLatch ready = new CountDownLatch(CONCURRENCY);
		CountDownLatch start = new CountDownLatch(1);
		Callable<PracticeAttemptResponse> request = () -> {
			ready.countDown();
			start.await();
			return call.call();
		};

		ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
		List<Throwable> failures = new ArrayList<>();
		try {
			List<Future<PracticeAttemptResponse>> futures = new ArrayList<>();
			for (int i = 0; i < CONCURRENCY; i++) {
				futures.add(executor.submit(request));
			}
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			for (Future<PracticeAttemptResponse> future : futures) {
				try {
					future.get(20, TimeUnit.SECONDS);
				} catch (ExecutionException executionException) {
					failures.add(executionException.getCause());
				}
			}
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
		return failures;
	}

	private Long createUser(String scenario) {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.test", "password-hash", scenario + "-" + suffix, BASE_NOW));
		createdUserIds.add(user.getId());
		return user.getId();
	}

	private Long attemptRowCount(Long userId) {
		return jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM practice_attempts WHERE user_id = ?", Long.class, userId);
	}

	private Long tutorialAccountRowCount(Long userId) {
		return jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM tutorial_accounts WHERE user_id = ?", Long.class, userId);
	}
}
