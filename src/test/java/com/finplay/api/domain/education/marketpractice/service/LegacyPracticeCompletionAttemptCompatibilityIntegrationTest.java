package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeCompletion;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketReflection;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
class LegacyPracticeCompletionAttemptCompatibilityIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 15, 10, 0);
	private static final LocalDateTime COMPLETED_AT = NOW.minusDays(1);
	private static final long REWARD = 5_000_000L;

	@Autowired
	private PracticeAttemptService attemptService;
	@Autowired
	private PracticeAttemptChartService chartService;
	@Autowired
	private InvestmentPracticeQueryService queryService;
	@Autowired
	private PracticeAttemptRepository attemptRepository;
	@Autowired
	private PracticeCompletionRepository completionRepository;
	@Autowired
	private PracticeMarketReflectionRepository reflectionRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private TestClock clock;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private ExecutorService executor;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		executor = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
	}

	@Test
	void ensureBackfillsCompletedReplayWithoutChangingCompletionRewardOrProgressAndChartSupportsNullRisk() {
		LegacyFixture fixture = createLegacyCompletion(Market.CRYPTO, "sequential", false);
		InvestmentPracticeResponse before = queryService.getProgress(fixture.userId(), Market.CRYPTO);
		long completionCount = completionRepository.count();
		long reflectionCount = reflectionRepository.count();
		long cashBefore = accountRepository.findById(fixture.accountId()).orElseThrow().getCashBalance();

		PracticeAttemptResponse ensured = attemptService.ensureAttempt(fixture.userId(), Market.CRYPTO);

		assertThat(ensured.mode()).isEqualTo("REPLAY");
		assertThat(ensured.status()).isEqualTo("COMPLETED");
		assertThat(ensured.instrumentId()).isEqualTo(fixture.instrumentId());
		assertThat(ensured.completedAt()).isEqualTo(COMPLETED_AT);
		assertThat(ensured.riskSnapshot()).isNull();
		assertThat(completionRepository.count()).isEqualTo(completionCount);
		assertThat(reflectionRepository.count()).isEqualTo(reflectionCount);
		Account unchanged = accountRepository.findById(fixture.accountId()).orElseThrow();
		assertThat(unchanged.getCashBalance()).isEqualTo(cashBefore);

		InvestmentPracticeResponse after = queryService.getProgress(fixture.userId(), Market.CRYPTO);
		assertThat(after.status()).isEqualTo("COMPLETED");
		assertThat(after.completedAt()).isEqualTo(before.completedAt());
		assertThat(after.rewardAmount()).isEqualTo(before.rewardAmount());
		assertThat(after.steps()).isEqualTo(before.steps());
		assertThat(after.attempt()).usingRecursiveComparison()
			.ignoringFields("tutorialCashBalance", "tutorialAvailableCash", "tutorialRealizedPnl")
			.isEqualTo(ensured);

		PracticeTutorialChartResponse chart = chartService.getChart(fixture.userId(), Market.CRYPTO);
		assertThat(chart.attemptId()).isEqualTo(ensured.attemptId());
		assertThat(chart.runNumber()).isEqualTo(ensured.runNumber());
		assertThat(chart.instrumentId()).isEqualTo(fixture.instrumentId());
		assertThat(chart.candles()).hasSize(30);
		clock.set(NOW.plusDays(30));
		PracticeTutorialChartResponse chartAfterClockAdvance = chartService.getChart(fixture.userId(), Market.CRYPTO);
		assertThat(chartAfterClockAdvance).isEqualTo(chart);

		PracticeAttemptResponse repeated = attemptService.ensureAttempt(fixture.userId(), Market.CRYPTO);
		assertThat(repeated).isEqualTo(ensured);
		assertThat(attemptRepository.findAll().stream()
			.filter(attempt -> attempt.getUserId().equals(fixture.userId()))
			.filter(attempt -> attempt.getMarket() == Market.CRYPTO))
			.hasSize(1);
	}

	@Test
	void twoConcurrentEnsuresCreateOnlyOneCompletedReplayAttempt() throws Exception {
		LegacyFixture fixture = createLegacyCompletion(Market.STOCK, "concurrent", true);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		Future<PracticeAttemptResponse> first = executor.submit(
			() -> ensureAfterBarrier(fixture.userId(), Market.STOCK, ready, start));
		Future<PracticeAttemptResponse> second = executor.submit(
			() -> ensureAfterBarrier(fixture.userId(), Market.STOCK, ready, start));
		assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
		start.countDown();

		PracticeAttemptResponse firstResponse = first.get(15, TimeUnit.SECONDS);
		PracticeAttemptResponse secondResponse = second.get(15, TimeUnit.SECONDS);
		assertThat(firstResponse.mode()).isEqualTo("REPLAY");
		assertThat(secondResponse.mode()).isEqualTo("REPLAY");
		assertThat(firstResponse.status()).isEqualTo("COMPLETED");
		assertThat(secondResponse.status()).isEqualTo("COMPLETED");
		assertThat(firstResponse.attemptId()).isEqualTo(secondResponse.attemptId());
		assertThat(attemptRepository.findAll().stream()
			.filter(attempt -> attempt.getUserId().equals(fixture.userId()))
			.filter(attempt -> attempt.getMarket() == Market.STOCK))
			.hasSize(1);
		assertThat(completionRepository.findByUserIdAndTutorialKey(
			fixture.userId(), "INVESTMENT_PRACTICE_V1")).isPresent();
	}

	@Test
	void ensureReturnsCurrentInProgressStateWhenCompletionCoexistsWithNonCompletedAttemptWithoutChangingPendingReservation() {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			"reconcile-" + suffix + "@finplay.com", "password-hash", "reconcile-" + suffix,
			NOW.minusDays(2)));
		Account account = Account.create(user, Market.CRYPTO, NOW.minusDays(2));
		account.addCash(REWARD);
		account = accountRepository.saveAndFlush(account);
		Instrument instrument = instrumentRepository.findByMarketAndSymbol(Market.CRYPTO, "SANDBOX_COIN_1")
			.orElseThrow();

		PracticeAttemptResponse active = attemptService.ensureAttempt(user.getId(), Market.CRYPTO);
		PracticeAttemptResponse selected = attemptService.selectInstrument(user.getId(), Market.CRYPTO,
			instrument.getId());
		account.reserveCash(100_050L);
		accountRepository.saveAndFlush(account);
		Order pending = orderRepository.saveAndFlush(Order.createLimitPendingForPracticeAttempt(
			user, account, instrument, OrderSide.BUY, new BigDecimal("0.1"), new BigDecimal("1000000"),
			active.attemptId(), active.runNumber(), "legacy-reconcile-" + suffix, "a".repeat(64), NOW));

		Holding holding = Holding.create(account, instrument, NOW.minusDays(2));
		holding.applyBuy(BigDecimal.ONE, new BigDecimal("10000"), NOW.minusDays(2));
		holding = holdingRepository.saveAndFlush(holding);
		String tutorialKey = "COIN_PRACTICE_V1";
		insertCompletedProgress(user.getId(), tutorialKey);
		PracticeMarketReflection reflection = reflectionRepository.saveAndFlush(
			PracticeMarketReflection.create(user.getId(), holding, tutorialKey, (short)1, "기존 완료 복기", COMPLETED_AT));
		completionRepository
			.saveAndFlush(PracticeCompletion.create(user.getId(), tutorialKey, reflection, COMPLETED_AT));

		long orderCount = orderRepository.count();
		long completionCount = completionRepository.count();
		long reflectionCount = reflectionRepository.count();

		PracticeAttemptResponse ensured = attemptService.ensureAttempt(user.getId(), Market.CRYPTO);

		assertThat(ensured.status()).isEqualTo("IN_PROGRESS");
		assertThat(ensured.attemptId()).isEqualTo(selected.attemptId());
		assertThat(ensured.runNumber()).isEqualTo(selected.runNumber());
		assertThat(ensured.instrumentId()).isEqualTo(instrument.getId());
		assertThat(orderRepository.findById(pending.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PENDING);
		assertThat(accountRepository.findById(account.getId()).orElseThrow().getReservedCash()).isEqualTo(100_050L);
		assertThat(orderRepository.count()).isEqualTo(orderCount);
		assertThat(completionRepository.count()).isEqualTo(completionCount);
		assertThat(reflectionRepository.count()).isEqualTo(reflectionCount);
	}

	private PracticeAttemptResponse ensureAfterBarrier(
		Long userId, Market market, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
		ready.countDown();
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 ensure 시작 장벽 시간이 초과되었습니다.");
		}
		return attemptService.ensureAttempt(userId, market);
	}

	private LegacyFixture createLegacyCompletion(Market market, String scenario, boolean tutorialSample) {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.com", "password-hash", scenario + "-" + suffix,
			NOW.minusDays(2)));
		Account account = Account.create(
			user, Market.valueOf(market.name()), NOW.minusDays(2));
		account.addCash(REWARD);
		account = accountRepository.saveAndFlush(account);
		Instrument instrument;
		if (tutorialSample) {
			String symbol = market == Market.STOCK ? "SANDBOX_STK_1" : "SANDBOX_COIN_1";
			instrument = instrumentRepository.findByMarketAndSymbol(market, symbol).orElseThrow();
		} else {
			String symbol = market == Market.STOCK ? "005930" : "BTC";
			instrument = instrumentRepository.findByMarketAndSymbol(market, symbol).orElseThrow();
		}
		Holding holding = Holding.create(account, instrument, NOW.minusDays(2));
		holding.applyBuy(BigDecimal.ONE, new BigDecimal("10000"), NOW.minusDays(2));
		holding = holdingRepository.saveAndFlush(holding);
		String tutorialKey = market == Market.STOCK ? "INVESTMENT_PRACTICE_V1" : "COIN_PRACTICE_V1";
		insertCompletedProgress(user.getId(), tutorialKey);
		PracticeMarketReflection reflection = reflectionRepository.saveAndFlush(
			PracticeMarketReflection.create(user.getId(), holding, tutorialKey, (short)1, "기존 완료 복기", COMPLETED_AT));
		PracticeCompletion completion = completionRepository.saveAndFlush(
			PracticeCompletion.create(user.getId(), tutorialKey, reflection, COMPLETED_AT));
		assertThat(attemptRepository.findByUserIdAndMarket(user.getId(), market)).isEmpty();
		return new LegacyFixture(user.getId(), account.getId(), instrument.getId(), completion.getId());
	}

	private void insertCompletedProgress(Long userId, String tutorialKey) {
		jdbcTemplate.update(
			"INSERT INTO practice_progresses "
				+ "(user_id, tutorial_key, status, started_at, completed_at) VALUES (?, ?, 'COMPLETED', ?, ?)",
			userId, tutorialKey, NOW.minusDays(2), COMPLETED_AT);
	}

	private record LegacyFixture(Long userId, Long accountId, Long instrumentId, Long completionId) {
	}
}
