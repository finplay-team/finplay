package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeCompletion;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketReflection;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.domain.education.repository.PracticeProgressRepository;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class PracticeAttemptRestartRecompletionIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 17, 9, 0);
	private static final long COMPLETION_REWARD = 5_000_000L;
	private static final BigDecimal BUY_QUANTITY = new BigDecimal("10");
	private static final BigDecimal SELL_QUANTITY = new BigDecimal("4");

	@Autowired
	private PracticeAttemptService practiceAttemptService;
	@Autowired
	private PracticeAttemptRestartService practiceAttemptRestartService;
	@Autowired
	private PracticeHoldingObservationService observationService;
	@Autowired
	private PracticeHoldingReflectionService reflectionService;
	@Autowired
	private OrderService orderService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private PracticeAttemptRepository attemptRepository;
	@Autowired
	private PracticeCompletionRepository completionRepository;
	@Autowired
	private PracticeMarketObservationRepository observationRepository;
	@Autowired
	private PracticeMarketReflectionRepository reflectionRepository;
	@Autowired
	private PracticeProgressRepository progressRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private TradeRepository tradeRepository;
	@Autowired
	private TestClock clock;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private ExecutorService executor;

	@AfterEach
	void tearDown() {
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	@Test
	void restartAfterFirstCompletionRecompletesWithoutAdditionalRewardOrEvidenceRowGrowth() {
		Market market = Market.STOCK;
		Fixture fixture = createFixture(market, "recomplete");

		RunOutcome firstRun = completeCurrentRun(fixture, market, "최초 실행 복기");
		assertThat(firstRun.response().rewardGranted()).isTrue();
		assertThat(firstRun.response().reflectionId()).isNotNull();
		Account afterFirstReward = refreshedAccount(fixture.userId(), market);
		assertThat(afterFirstReward.getCashBalance()).isEqualTo(firstRun.cashBeforeCompletion() + COMPLETION_REWARD);

		long completionCountAfterFirst = completionRepository.count();
		long reflectionCountAfterFirst = reflectionRepository.count();
		long progressCountAfterFirst = progressRepository.count();
		LocalDateTime firstCompletedAt = attemptRepository.findById(fixture.attemptId())
			.orElseThrow().getCompletedAt();

		clock.set(LocalDateTime.now(clock).plusSeconds(300));
		PracticeAttemptResponse restarted = practiceAttemptRestartService.restart(fixture.userId(), market);
		assertThat(restarted.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(restarted.runNumber()).isEqualTo(2L);

		RunOutcome secondRun = completeCurrentRun(fixture, market, "재시작 후 두 번째 실행 복기");
		assertThat(secondRun.response().rewardGranted()).isFalse();
		assertThat(secondRun.response().reflectionId()).isNull();
		assertThat(secondRun.response().answer()).isEqualTo("재시작 후 두 번째 실행 복기");

		Account afterRecompletion = refreshedAccount(fixture.userId(), market);
		assertThat(afterRecompletion.getCashBalance()).isEqualTo(secondRun.cashBeforeCompletion());

		assertThat(completionRepository.count()).isEqualTo(completionCountAfterFirst);
		assertThat(reflectionRepository.count()).isEqualTo(reflectionCountAfterFirst);
		assertThat(progressRepository.count()).isEqualTo(progressCountAfterFirst);

		PracticeAttempt reCompletedAttempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		assertThat(reCompletedAttempt.getStatus().name()).isEqualTo("COMPLETED");
		assertThat(reCompletedAttempt.getRunNumber()).isEqualTo(2L);
		assertThat(reCompletedAttempt.getCompletedAt()).isAfter(firstCompletedAt);
	}

	@Test
	void legacyPreDeploymentCompletionRestartsAndRecompletesWithoutBackfillOrReward() {
		Market market = Market.STOCK;
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		LocalDateTime legacyCompletedAt = BASE_NOW.minusDays(30);
		User user = userRepository.saveAndFlush(User.create(
			"legacy-" + suffix + "@finplay.com", "password-hash", "legacy-" + suffix, legacyCompletedAt.minusDays(1)));
		Account account = Account.create(user, Market.STOCK,
			legacyCompletedAt.minusDays(1));
		account.addCash(COMPLETION_REWARD);
		account = accountRepository.saveAndFlush(account);
		Instrument instrument = instrumentRepository.findByMarketAndSymbol(market, "SANDBOX_STK_1").orElseThrow();
		Holding legacyHolding = Holding.create(account, instrument, legacyCompletedAt.minusDays(1));
		legacyHolding.applyBuy(BigDecimal.ONE, new BigDecimal("10000"), legacyCompletedAt.minusDays(1));
		legacyHolding = holdingRepository.saveAndFlush(legacyHolding);
		String tutorialKey = PracticeIntentionService.TUTORIAL_KEY;
		jdbcTemplate.update(
			"INSERT INTO practice_progresses (user_id, tutorial_key, status, started_at, completed_at) "
				+ "VALUES (?, ?, 'COMPLETED', ?, ?)",
			user.getId(), tutorialKey, legacyCompletedAt.minusDays(1), legacyCompletedAt);
		PracticeMarketReflection legacyReflection = reflectionRepository.saveAndFlush(PracticeMarketReflection
			.create(user.getId(), legacyHolding, tutorialKey, (short)1, "배포 이전 완료 복기", legacyCompletedAt));
		completionRepository.saveAndFlush(
			PracticeCompletion.create(user.getId(), tutorialKey, legacyReflection, legacyCompletedAt));
		assertThat(attemptRepository.findByUserIdAndMarket(user.getId(), market)).isEmpty();

		clock.set(BASE_NOW);
		PracticeAttemptResponse ensured = practiceAttemptService.ensureAttempt(user.getId(), market);
		assertThat(ensured.mode()).isEqualTo("REPLAY");
		assertThat(ensured.status()).isEqualTo("COMPLETED");

		long completionCountBefore = completionRepository.count();
		long reflectionCountBefore = reflectionRepository.count();
		long progressCountBefore = progressRepository.count();

		PracticeAttemptResponse restarted = practiceAttemptRestartService.restart(user.getId(), market);
		assertThat(restarted.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(restarted.runNumber()).isEqualTo(2L);

		Fixture fixture = new Fixture(user.getId(), account.getId(), instrument.getId(), ensured.attemptId());
		RunOutcome recompletionRun = completeCurrentRun(fixture, market, "배포 이전 완료자의 재완료");

		assertThat(recompletionRun.response().rewardGranted()).isFalse();
		assertThat(recompletionRun.response().reflectionId()).isNull();
		Account cashAfterRecompletion = refreshedAccount(user.getId(), market);
		assertThat(cashAfterRecompletion.getCashBalance()).isEqualTo(recompletionRun.cashBeforeCompletion());
		assertThat(completionRepository.count()).isEqualTo(completionCountBefore);
		assertThat(reflectionRepository.count()).isEqualTo(reflectionCountBefore);
		assertThat(progressRepository.count()).isEqualTo(progressCountBefore);

		PracticeAttempt afterRecompletion = attemptRepository.findById(ensured.attemptId()).orElseThrow();
		assertThat(afterRecompletion.getStatus().name()).isEqualTo("COMPLETED");
		assertThat(afterRecompletion.getRunNumber()).isEqualTo(2L);
		assertThat(afterRecompletion.getCompletedAt()).isAfter(legacyCompletedAt);
	}

	@Test
	void twoConcurrentRecompletionRequestsGrantRewardZeroAdditionalTimes() throws Exception {
		Market market = Market.STOCK;
		Fixture fixture = createFixture(market, "concurrent-recompletion");
		completeCurrentRun(fixture, market, "최초 실행 복기");

		clock.set(LocalDateTime.now(clock).plusSeconds(300));
		practiceAttemptRestartService.restart(fixture.userId(), market);
		Holding secondRunHolding = buildSecondRunEvidence(fixture, market);
		Account beforeRace = refreshedAccount(fixture.userId(), market);
		long completionCountBeforeRace = completionRepository.count();
		long reflectionCountBeforeRace = reflectionRepository.count();

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		executor = Executors.newFixedThreadPool(2);
		Future<PracticeHoldingReflectionResponse> first = executor.submit(() -> recompleteAfterBarrier(
			fixture.userId(), secondRunHolding.getId(), "동시 요청 1", ready, start));
		Future<PracticeHoldingReflectionResponse> second = executor.submit(() -> recompleteAfterBarrier(
			fixture.userId(), secondRunHolding.getId(), "동시 요청 2", ready, start));
		assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
		start.countDown();

		int successCount = 0;
		int alreadyCompletedCount = 0;
		for (Future<PracticeHoldingReflectionResponse> future : java.util.List.of(first, second)) {
			try {
				PracticeHoldingReflectionResponse response = future.get(20, TimeUnit.SECONDS);
				assertThat(response.rewardGranted()).isFalse();
				successCount++;
			} catch (ExecutionException executionException) {
				assertThat(executionException.getCause()).isInstanceOfSatisfying(BusinessException.class,
					exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_ALREADY_COMPLETED));
				alreadyCompletedCount++;
			}
		}

		assertThat(successCount).isEqualTo(1);
		assertThat(alreadyCompletedCount).isEqualTo(1);
		Account afterRace = refreshedAccount(fixture.userId(), market);
		assertThat(afterRace.getCashBalance()).isEqualTo(beforeRace.getCashBalance());
		assertThat(completionRepository.count()).isEqualTo(completionCountBeforeRace);
		assertThat(reflectionRepository.count()).isEqualTo(reflectionCountBeforeRace);
		PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		assertThat(attempt.getStatus().name()).isEqualTo("COMPLETED");
		assertThat(attempt.getRunNumber()).isEqualTo(2L);
	}

	private PracticeHoldingReflectionResponse recompleteAfterBarrier(
		Long userId, Long holdingId, String answer, CountDownLatch ready, CountDownLatch start)
		throws InterruptedException {
		ready.countDown();
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("동시 재완료 시작 장벽 시간이 초과되었습니다.");
		}
		return reflectionService.createReflection(userId,
			new PracticeHoldingReflectionCreateRequest(holdingId, answer));
	}

	private RunOutcome completeCurrentRun(Fixture fixture, Market market, String answer) {
		Holding holding = buildSecondRunEvidence(fixture, market);
		Account beforeCompletion = refreshedAccount(fixture.userId(), market);
		PracticeHoldingReflectionResponse response = reflectionService.createReflection(fixture.userId(),
			new PracticeHoldingReflectionCreateRequest(holding.getId(), answer));
		return new RunOutcome(response, beforeCompletion.getCashBalance());
	}

	private Holding buildSecondRunEvidence(Fixture fixture, Market market) {
		LocalDateTime runStart = LocalDateTime.now(clock);
		practiceAttemptService.selectInstrument(fixture.userId(), market, fixture.instrumentId());

		clock.set(runStart.plusSeconds(2));
		orderService.createOrder(fixture.userId(), idempotency("buy"),
			marketOrder(market, fixture.instrumentId(), OrderSide.BUY, BUY_QUANTITY));
		Holding holding = holdingRepository
			.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.orElseThrow();
		createQualifyingObservations(fixture.userId(), holding.getId(), runStart.plusSeconds(12));

		clock.set(runStart.plusSeconds(150));
		orderService.createOrder(fixture.userId(), idempotency("sell"),
			marketOrder(market, fixture.instrumentId(), OrderSide.SELL, SELL_QUANTITY));
		clock.set(runStart.plusSeconds(160));
		return holding;
	}

	private Fixture createFixture(Market market, String scenario) {
		clock.set(BASE_NOW);
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.com", "password-hash", scenario + "-" + suffix, BASE_NOW));
		Account account = accountRepository.saveAndFlush(Account.create(
			user, Market.valueOf(market.name()), BASE_NOW));
		String symbol = market == Market.STOCK ? "SANDBOX_STK_1" : "SANDBOX_COIN_1";
		Instrument instrument = instrumentRepository.findByMarketAndSymbol(market, symbol).orElseThrow();
		PracticeAttemptResponse ensured = practiceAttemptService.ensureAttempt(user.getId(), market);
		return new Fixture(user.getId(), account.getId(), instrument.getId(), ensured.attemptId());
	}

	private void createQualifyingObservations(Long userId, Long holdingId, LocalDateTime firstAt) {
		clock.set(firstAt);
		observationService.createObservation(userId, new PracticeHoldingObservationCreateRequest(holdingId));
		clock.set(firstAt.plusMinutes(1));
		observationService.createObservation(userId, new PracticeHoldingObservationCreateRequest(holdingId));
		clock.set(firstAt.plusMinutes(2));
		observationService.createObservation(userId, new PracticeHoldingObservationCreateRequest(holdingId));
	}

	private Account refreshedAccount(Long userId, Market market) {
		return accountRepository.findByUserIdAndMarket(
			userId, Market.valueOf(market.name())).orElseThrow();
	}

	private static OrderCreateRequest marketOrder(
		Market market, Long instrumentId, OrderSide side, BigDecimal quantity) {
		return new OrderCreateRequest(market, instrumentId, side, "MARKET", quantity);
	}

	private static String idempotency(String scenario) {
		return scenario + "-" + UUID.randomUUID();
	}

	private record Fixture(Long userId, Long accountId, Long instrumentId, Long attemptId) {
	}

	private record RunOutcome(PracticeHoldingReflectionResponse response, long cashBeforeCompletion) {
	}
}
