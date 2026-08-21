// 재시작·tick·진입이 같은 사용자에게 동시에 들어와도 교착으로 500이 새지 않는지 실제 MySQL로 검증한다
// (이슈 #491의 두 번째 재현 경로 — 프론트가 8~32회 관측한 재시작 ↔ tick 폴링 조합).
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
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

// @Transactional을 붙이지 않는다 — 작업 스레드가 각자 트랜잭션을 열어야 교착 자체가 재현된다.
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
	private com.finplay.api.market.service.TutorialScenarioScriptLoader tutorialScenarioScriptLoader;
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

	// 이 클래스가 만든 행만 지운다.
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

	/**
	 * 이슈 #491 코멘트가 등록한 두 번째 재현 경로다. 재시작은 attempt → 주문 → 계좌 → 보유 → 튜토리얼 계좌
	 * 순으로, tick은 attempt → 주문 → 계좌 → 튜토리얼 계좌 → 보유 순으로 잠근다 — 뒤쪽 두 자원의 순서가
	 * 서로 뒤집혀 있어 교착 후보로 지목됐다. 둘 다 attempt를 <b>가장 먼저</b> 잠그므로 같은 사용자에 대해서는
	 * 그 지점에서 직렬화되어야 하고, 그렇다면 뒤집힌 순서가 실제 순환을 만들지 못한다는 것이 이 테스트가
	 * 고정하려는 것이다.
	 */
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

	/**
	 * 프론트가 실제로 관측한 조합에 더 가깝다 — 튜토리얼 화면은 마운트할 때 진입을 부르고, 3초마다 tick을
	 * 폴링하며, 사용자가 재시작을 누른다. 셋이 겹치는 순간을 그대로 만든다.
	 */
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

	/**
	 * 서로 다른 사용자가 같은 샘플 종목으로 동시에 움직이는 경우다. 이슈 #491 코멘트의 4번 발견(기존 행을
	 * 만난 {@code INSERT IGNORE}가 PRIMARY supremum에 X next-key 잠금을 잡아 <b>표 단위</b>로 직렬화된다)이
	 * 사용자를 건너 얽히는 통로였으므로, 진입이 더 이상 무조건 INSERT하지 않게 된 지금 그 통로가 닫혔는지
	 * 함께 확인한다.
	 */
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

	// 교착만 실패로 본다. 재시작·tick이 서로를 앞질러 BusinessException(예: 이미 다음 run이라 정산할 것이
	// 없음)으로 끝나는 것은 정상이며, 이 이슈가 막으려는 것은 500으로 새는 CannotAcquireLockException이다.
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

	// 재시작이 정리할 것과 tick이 정산할 것이 둘 다 있는 상태를 만든다 — 현재 run에 귀속된 PENDING 지정가
	// 주문과 보유가 있어야 두 경로가 주문·계좌·보유·튜토리얼 계좌까지 실제로 내려간다.
	private Fixture fixture(String scenario) {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.test", "hash", scenario + "-" + suffix, BASE_NOW));
		userIds.add(user.getId());

		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, BASE_NOW));
		accountIds.add(account.getId());

		Instrument instrument = Instrument.create(
			Market.CRYPTO, "R" + suffix.toUpperCase(), scenario, BigDecimal.ONE, 5_000L, true, BASE_NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);
		instrumentIds.add(instrument.getId());

		// **대본 실행(생성기 버전 2)으로 만든다.** 프론트가 교착을 관측한 CRYPTO 튜토리얼이 이 경로이고,
		// 버전 1과 달리 tick이 PracticeScenarioProgressService.advance로 들어가 건너뛴 가상 분마다 순차
		// 정산한다 — 한 tick이 잡는 잠금이 훨씬 많아 재시작과 겹칠 창이 그만큼 넓다.
		PracticeAttempt attempt = PracticeAttempt.create(user.getId(), Market.CRYPTO, BASE_NOW.minusHours(1));
		attempt.selectInstrument(
			instrument, BASE_NOW.minusMinutes(10), BASE_NOW.toLocalDate(), 123L,
			com.finplay.api.market.service.TutorialPriceGenerator.VERSION_2,
			tutorialScenarioScriptLoader.firstScriptId(Market.CRYPTO), BASE_NOW.minusMinutes(10));
		practiceAttemptRepository.saveAndFlush(attempt);

		Holding holding = Holding.create(account, instrument, BASE_NOW);
		holding.applyBuy(new BigDecimal("1"), BigDecimal.valueOf(900_000), BASE_NOW);
		// 아래 SELL PENDING 주문이 잡아 둔 예약이다 — 재시작의 정리 경로가 이 예약을 되돌린다.
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
