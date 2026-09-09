package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.global.exception.BusinessException;
import java.math.BigDecimal;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class PracticeAttemptRestartTickConcurrencyIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 21, 10, 0, 0);
	private static final BigDecimal LIMIT_PRICE = new BigDecimal("1000000");
	private static final int ROUNDS = 8;

	@Autowired
	private PracticeAttemptRestartService practiceAttemptRestartService;
	@Autowired
	private PracticeAttemptChartService practiceAttemptChartService;
	@Autowired
	private PracticeAttemptDeadlockRetryService practiceAttemptDeadlockRetryService;
	@Autowired
	private PracticeAttemptRepository practiceAttemptRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private com.finplay.api.domain.market.service.TutorialScenarioScriptLoader tutorialScenarioScriptLoader;
	@Autowired
	private TestClock clock;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final List<Long> userIds = new ArrayList<>();
	private final List<Long> accountIds = new ArrayList<>();
	private final List<Long> instrumentIds = new ArrayList<>();

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@AfterEach
	void cleanUp() {
		for (Long accountId : accountIds) {
			jdbcTemplate.update(
				"DELETE FROM holding_lots WHERE holding_id IN (SELECT id FROM holdings WHERE account_id = ?)",
				accountId);
			jdbcTemplate.update("DELETE FROM trades WHERE account_id = ?", accountId);
			jdbcTemplate.update("DELETE FROM orders WHERE account_id = ?", accountId);
			jdbcTemplate.update("DELETE FROM holdings WHERE account_id = ?", accountId);
		}
		for (Long userId : userIds) {
			jdbcTemplate.update(
				"DELETE FROM practice_risk_snapshots WHERE attempt_id IN "
					+ "(SELECT id FROM practice_attempts WHERE user_id = ?)",
				userId);
			jdbcTemplate.update("DELETE FROM exit_plans WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM practice_attempts WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM practice_progresses WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM tutorial_accounts WHERE user_id = ?", userId);
		}
		for (Long accountId : accountIds) {
			jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
		}
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
		}
		for (Long instrumentId : instrumentIds) {
			jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", instrumentId);
		}
		userIds.clear();
		accountIds.clear();
		instrumentIds.clear();
	}

	@Test
	void concurrentRestartAndTickNeverDeadlock() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			Fixture fixture = fixture("restart-tick-" + round);

			List<Throwable> failures = runConcurrently(List.of(
				() -> practiceAttemptRestartService.restart(fixture.userId(), Market.CRYPTO),
				() -> practiceAttemptChartService.tick(fixture.userId(), Market.CRYPTO)));

			assertNoDeadlock(failures, "round " + round + " — 재시작 ↔ tick");
		}
	}

	@Test
	void concurrentEntryRestartAndTickNeverDeadlock() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			Fixture fixture = fixture("entry-restart-tick-" + round);

			List<Throwable> failures = runConcurrently(List.of(
				() -> practiceAttemptDeadlockRetryService.ensureAttempt(fixture.userId(), Market.CRYPTO),
				() -> practiceAttemptRestartService.restart(fixture.userId(), Market.CRYPTO),
				() -> practiceAttemptChartService.tick(fixture.userId(), Market.CRYPTO)));

			assertNoDeadlock(failures, "round " + round + " — 진입 ↔ 재시작 ↔ tick");
		}
	}

	@Test
	void concurrentActivityAcrossDifferentUsersNeverDeadlocks() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			Fixture first = fixture("cross-user-a-" + round);
			Fixture second = fixture("cross-user-b-" + round);

			List<Throwable> failures = runConcurrently(List.of(
				() -> practiceAttemptDeadlockRetryService.ensureAttempt(first.userId(), Market.CRYPTO),
				() -> practiceAttemptRestartService.restart(first.userId(), Market.CRYPTO),
				() -> practiceAttemptDeadlockRetryService.ensureAttempt(second.userId(), Market.CRYPTO),
				() -> practiceAttemptChartService.tick(second.userId(), Market.CRYPTO)));

			assertNoDeadlock(failures, "round " + round + " — 사용자 간 동시 진행");
		}
	}

	private void assertNoDeadlock(List<Throwable> failures, String description) {
		List<Throwable> deadlocks = failures.stream()
			.filter(failure -> failure instanceof CannotAcquireLockException)
			.toList();
		assertThat(deadlocks).as(description).isEmpty();
		assertThat(failures.stream().filter(failure -> !(failure instanceof BusinessException)).toList())
			.as(description + " — 예상 밖 예외")
			.isEmpty();
	}

	private List<Throwable> runConcurrently(List<Callable<?>> calls) throws Exception {
		CountDownLatch ready = new CountDownLatch(calls.size());
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(calls.size());
		List<Throwable> failures = new ArrayList<>();
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (Callable<?> call : calls) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return call.call();
				}));
			}
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			for (Future<?> future : futures) {
				try {
					future.get(30, TimeUnit.SECONDS);
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

	private Fixture fixture(String scenario) {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.test", "hash", scenario + "-" + suffix, BASE_NOW));
		userIds.add(user.getId());

		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.domain.market.entity.Market.CRYPTO, BASE_NOW));
		accountIds.add(account.getId());

		Instrument instrument = Instrument.create(
			Market.CRYPTO, "R" + suffix.toUpperCase(), scenario, BigDecimal.ONE, 5_000L, true, BASE_NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);
		instrumentIds.add(instrument.getId());

		PracticeAttempt attempt = PracticeAttempt.create(user.getId(), Market.CRYPTO, BASE_NOW.minusHours(1));
		attempt.selectInstrument(
			instrument, BASE_NOW.minusMinutes(10), BASE_NOW.toLocalDate(), 123L,
			com.finplay.api.domain.market.service.TutorialPriceGenerator.VERSION_2,
			tutorialScenarioScriptLoader.firstScriptId(Market.CRYPTO), BASE_NOW.minusMinutes(10));
		practiceAttemptRepository.saveAndFlush(attempt);

		Holding holding = Holding.create(account, instrument, BASE_NOW);
		holding.applyBuy(new BigDecimal("1"), BigDecimal.valueOf(900_000), BASE_NOW);
		holding.reserveQuantity(new BigDecimal("0.5"));
		holdingRepository.saveAndFlush(holding);

		orderRepository.saveAndFlush(Order.createLimitPendingForPracticeAttempt(
			user, account, instrument, OrderSide.SELL, new BigDecimal("0.5"), LIMIT_PRICE,
			attempt.getId(), attempt.getRunNumber(), scenario + "-" + UUID.randomUUID(), "a".repeat(64), BASE_NOW));

		return new Fixture(user.getId());
	}

	private record Fixture(Long userId) {
	}
}
