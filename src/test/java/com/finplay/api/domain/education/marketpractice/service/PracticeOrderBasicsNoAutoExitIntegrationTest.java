package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class PracticeOrderBasicsNoAutoExitIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 20, 10, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.5");
	private static final BigDecimal ORDER_BASICS_ENTRY_PRICE = new BigDecimal("100000.00000000");
	private static final BigDecimal DEFAULT_STOP_LOSS = new BigDecimal("97000.00000000");
	private static final BigDecimal DEFAULT_TAKE_PROFIT = new BigDecimal("105000.00000000");
	private static final BigDecimal ORDER_BASICS_HIGH = new BigDecimal("112000.00000000");
	private static final BigDecimal ORDER_BASICS_LOW = new BigDecimal("88000.00000000");
	private static final BigDecimal LIMIT_BUY_PRICE = new BigDecimal("95000");
	private static final BigDecimal LIMIT_SELL_PRICE = new BigDecimal("90000");
	private static final BigDecimal STORY_ENTRY_PRICE = new BigDecimal("10180.00000000");
	private static final int SECONDS_PER_TICK = 30;
	private static final int TICK_ROUNDS = 4;

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private PracticeAttemptRepository attemptRepository;
	@Autowired
	private PracticeRiskSnapshotRepository riskSnapshotRepository;
	@Autowired
	private ExitPlanRepository exitPlanRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private OrderService orderService;
	@Autowired
	private LimitOrderService limitOrderService;
	@Autowired
	private TradeService tradeService;
	@Autowired
	private PracticeAttemptService attemptService;
	@Autowired
	private PracticeAttemptChartService chartService;
	@Autowired
	private PracticeStageProgressCalculationService stageProgressCalculationService;
	@Autowired
	private PracticeHoldingObservationService observationService;
	@Autowired
	private PracticeHoldingReflectionService reflectionService;
	@Autowired
	private InvestmentPracticeQueryService queryService;
	@Autowired
	private PracticeExitPlanReservationService exitPlanReservationService;
	@Autowired
	private TestClock clock;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@Test
	void orderBasicsBuyCreatesTheRiskBaselineButNoAutomaticExitPlan() {
		Fixture fixture = orderBasicsRun("ob-baseline");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);

		PracticeRiskSnapshot entry = latestSnapshot(fixture);
		assertThat(entry.getEntrySequence()).isEqualTo(1);
		assertThat(entry.getExitPreset()).isEqualTo(ExitPreset.DEFAULT);
		assertThat(entry.getEntryPrice()).isEqualByComparingTo(ORDER_BASICS_ENTRY_PRICE);
		assertThat(entry.getStopLossPrice()).isEqualByComparingTo(DEFAULT_STOP_LOSS);
		assertThat(entry.getTakeProfitPrice()).isEqualByComparingTo(DEFAULT_TAKE_PROFIT);

		assertThat(exitPlans(fixture)).isEmpty();
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(QUANTITY);
	}

	@Test
	void storyScriptBuyCreatesNoAutomaticExitPlanButTheUserCanReserveOneDirectly() {
		Fixture fixture = storyRun("ob-control");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);

		assertThat(latestSnapshot(fixture).getEntrySequence()).isEqualTo(1);
		assertThat(exitPlans(fixture)).isEmpty();
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);

		clock.set(BASE_NOW.plusSeconds(2));
		exitPlanReservationService.create(
			fixture.userId(), Market.CRYPTO, ExitRates.of(new BigDecimal("2"), new BigDecimal("8")));

		assertThat(exitPlans(fixture)).singleElement().satisfies(plan -> {
			assertThat(plan.getStatus()).isEqualTo(ExitPlanStatus.PENDING);
			assertThat(plan.getBaselinePrice()).isEqualByComparingTo(STORY_ENTRY_PRICE);
			assertThat(plan.getEntryPrice()).isEqualByComparingTo(STORY_ENTRY_PRICE);
			assertThat(plan.getStopLossPrice())
				.isEqualByComparingTo(STORY_ENTRY_PRICE.multiply(new BigDecimal("0.98")));
			assertThat(plan.getTakeProfitPrice())
				.isEqualByComparingTo(STORY_ENTRY_PRICE.multiply(new BigDecimal("1.08")));
			assertThat(plan.getPracticeAttemptRunNumber()).isEqualTo(1L);
		});
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(QUANTITY);

		assertThatThrownBy(() -> exitPlanReservationService.create(
			fixture.userId(), Market.CRYPTO, ExitRates.of(new BigDecimal("5"), new BigDecimal("3"))))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.EXIT_PLAN_ALREADY_EXISTS));
	}

	@Test
	void orderBasicsRunRejectsAUserDrivenReservationToo() {
		Fixture fixture = orderBasicsRun("ob-manual-blocked");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);

		assertThatThrownBy(() -> exitPlanReservationService.create(
			fixture.userId(), Market.CRYPTO, ExitRates.of(new BigDecimal("3"), new BigDecimal("5"))))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STAGE_LOCKED));
		assertThat(exitPlans(fixture)).isEmpty();
	}

	@Test
	void storyScriptRejectsAReservationWhileNothingIsHeld() {
		Fixture fixture = storyRun("ob-nothing-held");

		clock.set(BASE_NOW.plusSeconds(1));
		assertThatThrownBy(() -> exitPlanReservationService.create(
			fixture.userId(), Market.CRYPTO, ExitRates.of(new BigDecimal("3"), new BigDecimal("5"))))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STEP_LOCKED));
		assertThat(exitPlans(fixture)).isEmpty();
	}

	@Test
	void orderBasicsRunSurvivesTwoFullCyclesOfTicksAndThenCompletesAManualRoundTrip() {
		Fixture fixture = orderBasicsRun("ob-ticks");
		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);

		tickRounds(fixture);

		PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		assertThat(attempt.getScenarioStageId()).isEqualTo("ORDER_BASICS");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isGreaterThanOrEqualTo(60L);
		assertThat(attempt.getScenarioCandleHigh()).isGreaterThanOrEqualTo(ORDER_BASICS_HIGH);
		assertThat(attempt.getScenarioCandleLow()).isLessThanOrEqualTo(ORDER_BASICS_LOW);

		assertThat(exitPlans(fixture)).isEmpty();
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(QUANTITY);
		assertThat(riskSnapshotRepository.countByAttemptIdAndRunNumber(fixture.attemptId(), 1L)).isEqualTo(1L);
		assertThat(marketRoundTripCompleted(fixture)).isFalse();

		clock.set(BASE_NOW.plusSeconds((long)SECONDS_PER_TICK * TICK_ROUNDS + 10L));
		sell(fixture);

		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(marketRoundTripCompleted(fixture)).isTrue();
	}

	@Test
	void orderBasicsRunKeepsObservationAndReflectionWorkingEndToEnd() {
		Fixture fixture = orderBasicsRun("ob-evidence");
		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		Long holdingId = holdingRepository
			.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.orElseThrow()
			.getId();

		tickRounds(fixture);
		createQualifyingObservations(fixture.userId(), holdingId, BASE_NOW.plusSeconds(150));

		clock.set(BASE_NOW.plusSeconds(400));
		sell(fixture);
		clock.set(BASE_NOW.plusSeconds(410));
		reflectionService.createReflection(fixture.userId(),
			new PracticeHoldingReflectionCreateRequest(holdingId, "2단계에서 직접 사고 팔아 본 것을 복기합니다."));

		InvestmentPracticeResponse completed = queryService.getProgress(fixture.userId(), Market.CRYPTO);
		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
	}

	@Test
	void thirdEntryInAnOrderBasicsRunGetsSequenceThreeWithTheChosenPresetAndStillNoExitPlan() {
		Fixture fixture = orderBasicsRun("ob-reentry");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		clock.set(BASE_NOW.plusSeconds(2));
		sell(fixture);

		clock.set(BASE_NOW.plusSeconds(3));
		limitOrder(fixture, OrderSide.BUY, LIMIT_BUY_PRICE);
		clock.set(BASE_NOW.plusSeconds(33));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		clock.set(BASE_NOW.plusSeconds(39));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(QUANTITY);
		clock.set(BASE_NOW.plusSeconds(40));
		limitOrder(fixture, OrderSide.SELL, LIMIT_SELL_PRICE);
		clock.set(BASE_NOW.plusSeconds(42));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(marketRoundTripCompleted(fixture)).isTrue();

		attemptService.selectExitPreset(fixture.userId(), Market.CRYPTO, ExitPreset.RELAXED);
		clock.set(BASE_NOW.plusSeconds(43));
		buy(fixture);

		PracticeRiskSnapshot third = latestSnapshot(fixture);
		assertThat(third.getEntrySequence()).isEqualTo(3);
		assertThat(third.getExitPreset()).isEqualTo(ExitPreset.RELAXED);
		assertThat(riskSnapshotRepository.countByAttemptIdAndRunNumber(fixture.attemptId(), 1L)).isEqualTo(3L);
		assertThat(exitPlans(fixture)).isEmpty();
	}

	private void limitOrder(Fixture fixture, OrderSide side, BigDecimal limitPrice) {
		limitOrderService.createLimitOrder(
			fixture.userId(), "ob-reentry-limit-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), side, QUANTITY, limitPrice));
	}

	private void tickRounds(Fixture fixture) {
		for (int round = 1; round <= TICK_ROUNDS; round++) {
			clock.set(BASE_NOW.plusSeconds((long)SECONDS_PER_TICK * round));
			chartService.tick(fixture.userId(), Market.CRYPTO);
		}
	}

	private void createQualifyingObservations(Long userId, Long holdingId, LocalDateTime firstAt) {
		clock.set(firstAt);
		observationService.createObservation(userId, new PracticeHoldingObservationCreateRequest(holdingId));
		clock.set(firstAt.plusMinutes(1));
		observationService.createObservation(userId, new PracticeHoldingObservationCreateRequest(holdingId));
		clock.set(firstAt.plusMinutes(2));
		observationService.createObservation(userId, new PracticeHoldingObservationCreateRequest(holdingId));
	}

	private boolean marketRoundTripCompleted(Fixture fixture) {
		return stageProgressCalculationService
			.calculate(attemptRepository.findById(fixture.attemptId()).orElseThrow())
			.marketBuySellCompleted();
	}

	private void buy(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "ob-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.BUY, "MARKET", QUANTITY));
	}

	private void sell(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "ob-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.SELL, "MARKET", QUANTITY));
	}

	private PracticeRiskSnapshot latestSnapshot(Fixture fixture) {
		return riskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(fixture.attemptId(), 1L)
			.orElseThrow();
	}

	private List<ExitPlan> exitPlans(Fixture fixture) {
		return exitPlanRepository.findByPracticeAttemptIdAndPracticeAttemptRunNumber(fixture.attemptId(), 1L);
	}

	private BigDecimal reservedQuantity(Fixture fixture) {
		return holdingRepository.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.map(Holding::getReservedQuantity)
			.orElse(BigDecimal.ZERO);
	}

	private Fixture orderBasicsRun(String scenario) {
		return tutorialRun(
			scenario, TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1, "ORDER_BASICS", ORDER_BASICS_ENTRY_PRICE);
	}

	private Fixture storyRun(String scenario) {
		return tutorialRun(scenario, TutorialScenarioScriptId.CRYPTO_STORY_V1, "ACT2_RUMOR", STORY_ENTRY_PRICE);
	}

	private Fixture tutorialRun(
		String scenario, TutorialScenarioScriptId scriptId, String stageId, BigDecimal openPrice) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.com", "hash", scenario + "-" + suffix, BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "T" + suffix, scenario, BigDecimal.ONE, 0L, true, BASE_NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);

		attemptService.ensureAttempt(user.getId(), Market.CRYPTO);
		attemptService.selectInstrument(user.getId(), Market.CRYPTO, instrument.getId());
		PracticeAttempt attempt = attemptRepository.findByUserIdAndMarket(user.getId(), Market.CRYPTO).orElseThrow();
		ReflectionTestUtils.setField(attempt, "scenarioScriptId", scriptId);
		attempt.startScenarioProgress(stageId, openPrice, BASE_NOW);
		attemptRepository.saveAndFlush(attempt);
		assertThat(attempt.scenarioScriptId()).isEqualTo(scriptId);
		return new Fixture(user.getId(), account.getId(), instrument.getId(), attempt.getId());
	}

	private record Fixture(Long userId, Long accountId, Long instrumentId, Long attemptId) {
	}
}
