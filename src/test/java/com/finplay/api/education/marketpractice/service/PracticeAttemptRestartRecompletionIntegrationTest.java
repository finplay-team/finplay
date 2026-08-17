// 040 완료 attempt 재시작 후 재완료가 보상·evidence 3종 불변을 지키는지 실제 MySQL로 검증하는 통합 테스트
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeMarketReflection;
import com.finplay.api.education.marketpractice.domain.PracticeCompletion;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.education.repository.PracticeProgressRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
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

		// 1) 최초 완료 → 보상 지급 확인 (완료 호출 직전 잔고 대비 정확히 500만원 증가)
		RunOutcome firstRun = completeCurrentRun(fixture, market, "최초 실행 복기");
		assertThat(firstRun.response().rewardGranted()).isTrue();
		assertThat(firstRun.response().reflectionId()).isNotNull();
		Account afterFirstReward = refreshedAccount(fixture.userId(), market);
		assertThat(afterFirstReward.getCashBalance()).isEqualTo(firstRun.cashBeforeCompletion() + COMPLETION_REWARD);
		assertThat(afterFirstReward.getSandboxCashAdjustment())
			.isEqualTo(firstRun.sandboxAdjustmentBeforeCompletion() + COMPLETION_REWARD);

		long completionCountAfterFirst = completionRepository.count();
		long reflectionCountAfterFirst = reflectionRepository.count();
		long progressCountAfterFirst = progressRepository.count();
		LocalDateTime firstCompletedAt = attemptRepository.findById(fixture.attemptId())
			.orElseThrow().getCompletedAt();

		// 2) 재시작(POST .../restart)
		clock.set(LocalDateTime.now(clock).plusSeconds(300));
		PracticeAttemptResponse restarted = practiceAttemptRestartService.restart(fixture.userId(), market);
		assertThat(restarted.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(restarted.runNumber()).isEqualTo(2L);

		// 3) 재완료 — 완료 호출 직전 잔고와 동일해야 한다(보상 미지급)
		RunOutcome secondRun = completeCurrentRun(fixture, market, "재시작 후 두 번째 실행 복기");
		assertThat(secondRun.response().rewardGranted()).isFalse();
		assertThat(secondRun.response().reflectionId()).isNull();
		assertThat(secondRun.response().answer()).isEqualTo("재시작 후 두 번째 실행 복기");

		// 4) 계좌 현금 불변 확인
		Account afterRecompletion = refreshedAccount(fixture.userId(), market);
		assertThat(afterRecompletion.getCashBalance()).isEqualTo(secondRun.cashBeforeCompletion());
		assertThat(afterRecompletion.getSandboxCashAdjustment())
			.isEqualTo(secondRun.sandboxAdjustmentBeforeCompletion());

		// 5) practice_completions/practice_market_reflections/practice_progresses row count 불변 확인
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
		// TUTORIAL-RESTART-004: 이 기능 배포 이전에 이미 완료한 사용자를 흉내 내기 위해 practice_completions·
		// practice_progresses·practice_market_reflections을 attempt 없이 직접 seed한다(새 마이그레이션 없음,
		// 기존 스키마 그대로). 이후 ensureAttempt로 attempt가 지연 생성(reconcileCompletedReplay)된 뒤에도
		// 재시작·재완료 시 보상이 지급되지 않아야 "백필 없이 정확한 소급 판정"이 성립한다.
		Market market = Market.STOCK;
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		LocalDateTime legacyCompletedAt = BASE_NOW.minusDays(30);
		User user = userRepository.saveAndFlush(User.create(
			"legacy-" + suffix + "@finplay.com", "password-hash", "legacy-" + suffix, legacyCompletedAt.minusDays(1)));
		Account account = Account.create(user, com.finplay.api.account.domain.Market.STOCK,
			legacyCompletedAt.minusDays(1));
		account.addCash(COMPLETION_REWARD);
		account.addSandboxCashAdjustment(COMPLETION_REWARD);
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
		assertThat(cashAfterRecompletion.getSandboxCashAdjustment())
			.isEqualTo(recompletionRun.sandboxAdjustmentBeforeCompletion());
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
		assertThat(afterRace.getSandboxCashAdjustment()).isEqualTo(beforeRace.getSandboxCashAdjustment());
		assertThat(completionRepository.count()).isEqualTo(1);
		assertThat(reflectionRepository.count()).isEqualTo(1);
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
		return new RunOutcome(
			response, beforeCompletion.getCashBalance(), beforeCompletion.getSandboxCashAdjustment());
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
			user, com.finplay.api.account.domain.Market.valueOf(market.name()), BASE_NOW));
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
		// 클래스 레벨 @Transactional을 쓰지 않으므로(twoConcurrentRecompletionRequestsGrantRewardZeroAdditionalTimes가
		// 별도 스레드·트랜잭션을 실행해야 한다) 각 서비스 호출은 이미 커밋된 상태다 — 새로 조회하면 그대로 최신값이다.
		return accountRepository.findByUserIdAndMarket(
			userId, com.finplay.api.account.domain.Market.valueOf(market.name())).orElseThrow();
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

	private record RunOutcome(
		PracticeHoldingReflectionResponse response, long cashBeforeCompletion, long sandboxAdjustmentBeforeCompletion) {
	}
}
