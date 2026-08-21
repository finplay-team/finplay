// 같은 사용자·시장으로 튜토리얼 진입(ensureAttempt)이 동시에 들어와도 교착으로 500이 새지 않고 양쪽 다
// 정상 응답으로 끝나는지를 실제 MySQL과 스레드로 검증하는 통합 테스트다 (이슈 #491).
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.market.domain.Market;
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

// @Transactional을 붙이지 않는다 — 작업 스레드가 각자 트랜잭션을 열어야 교착 자체가 재현된다.
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class PracticeAttemptEntryConcurrencyIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 21, 10, 0, 0);
	// 교착은 타이밍에 달려 있어 1회 실행으로는 놓칠 수 있다. 수정 전 코드에서 이 횟수면 사실상 매번 재현된다.
	private static final int ROUNDS = 10;
	private static final int CONCURRENCY = 2;

	@Autowired
	private PracticeAttemptService practiceAttemptService;
	// 컨트롤러가 실제로 쓰는 진입 경로다 — 재시도 경계까지 포함한 배선을 실제 트랜잭션 위에서 검증한다.
	@Autowired
	private PracticeAttemptEntryService practiceAttemptEntryService;
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

	// 이 클래스가 만든 사용자만 지운다 — 다른 통합 테스트가 남긴 행은 건드리지 않는다.
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

	// attempt 행이 이미 있는 상태에서 동시에 들어오는 경우. INSERT IGNORE가 중복 키 인덱스 레코드에 S 잠금을
	// 잡고 곧바로 같은 레코드에 X(FOR UPDATE)를 요구하는 구조라, 두 트랜잭션이 서로의 S를 기다리는
	// 잠금 승격 교착이 된다 (이슈 #491의 추정 메커니즘).
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

	// attempt 행이 아직 없는 첫 진입이 동시에 들어오는 경우. 같은 유니크 키를 두 트랜잭션이 함께 삽입하려는
	// 구간이라 잠금 대기는 생기지만, 어느 쪽도 오류로 끝나서는 안 된다.
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

	// 위 두 테스트는 서비스를 직접 부르므로 컨트롤러가 실제로 쓰는 배선(PracticeAttemptEntryService)을
	// 한 번도 지나지 않는다. 이 수정의 핵심 전제가 "재시도 경계에 @Transactional이 없어 재시도가 트랜잭션
	// 밖에서 돈다"인데, 누가 그 빈에 @Transactional을 붙이면 재시도가 rollback-only 트랜잭션 안에서 돌아
	// 조용히 무력화된다. 그래서 운영 경로도 실제 트랜잭션 위에서 한 번 통과시킨다.
	@Test
	void concurrentEntryThroughProductionWiringAllSucceed() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			Long userId = createUser("entry-race-wiring-" + round);

			List<Throwable> failures = runConcurrently(
				userId, () -> practiceAttemptEntryService.ensureAttempt(userId, Market.CRYPTO));

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
