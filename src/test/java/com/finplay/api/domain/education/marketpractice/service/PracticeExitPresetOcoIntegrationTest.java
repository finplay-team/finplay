package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeEntryResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStageProgressResponse;
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
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.service.ExitPlanService;
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
import java.math.RoundingMode;
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
class PracticeExitPresetOcoIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 20, 10, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.5");
	private static final BigDecimal ENTRY_PRICE = new BigDecimal("10180.00000000");
	private static final BigDecimal BALANCED_STOP_LOSS = new BigDecimal("9874.60000000");
	private static final BigDecimal BALANCED_STOP_LOSS_FACTOR = new BigDecimal("0.97");
	private static final BigDecimal RELAXED_STOP_LOSS_FACTOR = new BigDecimal("0.95");
	private static final BigDecimal RELAXED_TAKE_PROFIT_FACTOR = new BigDecimal("1.08");
	private static final int PRICE_SCALE = 8;
	private static final LocalDateTime NARRATIVE_START = BASE_NOW.plusSeconds(50);

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
	private PracticeAttemptService attemptService;
	@Autowired
	private PracticeAttemptChartService chartService;
	@Autowired
	private PracticeAttemptRestartService restartService;
	@Autowired
	private PracticeAttemptScriptAdvanceService scriptAdvanceService;
	@Autowired
	private TradeService tradeService;
	@Autowired
	private LimitOrderService limitOrderService;
	@Autowired
	private PracticeStageProgressCalculationService stageProgressCalculationService;
	@Autowired
	private PracticeEntryComparisonService practiceEntryComparisonService;
	@Autowired
	private PracticeExitPlanReservationService exitPlanReservationService;
	@Autowired
	private ExitPlanService exitPlanService;
	@Autowired
	private TestClock clock;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
	}

	@Test
	void stopLossFillKeepsTheRunLedgerConsistentAndLetsTheUserReenterAndRestart() {
		Fixture fixture = tutorialRunAtRumorStageAfterBothRoundTrips("oco-stop");

		clock.set(NARRATIVE_START.plusSeconds(1));
		buy(fixture);
		assertThat(exitPlanRepository
			.findByPracticeAttemptIdAndPracticeAttemptRunNumber(fixture.attemptId(), 1L)).isEmpty();
		reserve(fixture, "3", "5");

		PracticeRiskSnapshot firstEntry = latestSnapshot(fixture);
		assertThat(firstEntry.getEntrySequence()).isEqualTo(3);
		assertThat(firstEntry.getExitPreset()).isEqualTo(ExitPreset.BALANCED);
		assertThat(firstEntry.getEntryPrice()).isEqualByComparingTo(ENTRY_PRICE);
		assertThat(firstEntry.getStopLossPrice()).isEqualByComparingTo(BALANCED_STOP_LOSS);

		ExitPlan reservation = onlyExitPlan(fixture);
		assertThat(reservation.getStatus()).isEqualTo(ExitPlanStatus.PENDING);
		assertThat(reservation.getBaselinePrice()).isEqualByComparingTo(ENTRY_PRICE);
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(QUANTITY);
		assertThatThrownBy(() -> attemptService.selectExitPreset(fixture.userId(), Market.CRYPTO, ExitPreset.RELAXED))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STEP_LOCKED));

		clock.set(NARRATIVE_START.plusSeconds(23));
		chartService.tick(fixture.userId(), Market.CRYPTO);

		ExitPlan filled = onlyExitPlan(fixture);
		assertThat(filled.getStatus()).isEqualTo(ExitPlanStatus.FILLED_STOP_LOSS);
		assertThat(filled.getTriggeredOrder()).isNotNull();
		assertThat(filled.getTriggeredOrder().getPracticeAttemptId()).isEqualTo(fixture.attemptId());
		assertThat(filled.getTriggeredOrder().getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);

		PracticeAttemptResponse afterStop = attemptService
			.selectExitPreset(fixture.userId(), Market.CRYPTO, ExitPreset.RELAXED);
		assertThat(afterStop.exitPresetLocked()).isFalse();
		assertThat(afterStop.selectedExitPreset()).isEqualTo("RELAXED");

		clock.set(NARRATIVE_START.plusSeconds(30));
		buy(fixture);
		reserve(fixture, "5", "8");

		PracticeRiskSnapshot secondEntry = latestSnapshot(fixture);
		assertThat(secondEntry.getEntrySequence()).isEqualTo(4);
		assertThat(secondEntry.getExitPreset()).isEqualTo(ExitPreset.RELAXED);

		List<Long> reReservedIds = exitPlanRepository.findPendingPracticeRunExitPlanIds(fixture.attemptId(), 1L);
		assertThat(reReservedIds).hasSize(1);
		ExitPlan reReserved = exitPlanRepository.findById(reReservedIds.get(0)).orElseThrow();
		BigDecimal reEntryPrice = secondEntry.getEntryPrice();
		assertThat(reReserved.getStopLossPrice())
			.isEqualByComparingTo(expectedPrice(reEntryPrice, RELAXED_STOP_LOSS_FACTOR));
		assertThat(reReserved.getTakeProfitPrice())
			.isEqualByComparingTo(expectedPrice(reEntryPrice, RELAXED_TAKE_PROFIT_FACTOR));
		assertThat(reReserved.getStopLossPrice()).isEqualByComparingTo(secondEntry.getStopLossPrice());
		assertThat(reReserved.getTakeProfitPrice()).isEqualByComparingTo(secondEntry.getTakeProfitPrice());
		assertThat(reReserved.getStopLossPrice())
			.isNotEqualByComparingTo(expectedPrice(reEntryPrice, BALANCED_STOP_LOSS_FACTOR));

		clock.set(NARRATIVE_START.plusSeconds(40));
		PracticeAttemptResponse restarted = restartService.restart(fixture.userId(), Market.CRYPTO);
		assertThat(restarted.runNumber()).isEqualTo(2L);
		assertThat(exitPlanRepository.findPendingPracticeRunExitPlanIds(fixture.attemptId(), 1L)).isEmpty();
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void stopLossDoesNotCompleteTheMarketStageButAManualSellDoes() {
		Fixture fixture = tutorialRunAtRumorStage("oco-stage");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		reserve(fixture, "3", "5");
		assertThat(stageProgress(fixture).marketBuySellCompleted()).isFalse();

		clock.set(BASE_NOW.plusSeconds(23));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		assertThat(onlyExitPlan(fixture).getStatus()).isEqualTo(ExitPlanStatus.FILLED_STOP_LOSS);
		assertThat(onlyExitPlan(fixture).getTriggeredOrder().getOrderType()).isEqualTo(OrderType.MARKET);
		assertThat(stageProgress(fixture).marketBuySellCompleted()).isFalse();

		clock.set(BASE_NOW.plusSeconds(30));
		buy(fixture);

		clock.set(BASE_NOW.plusSeconds(31));
		sell(fixture);

		PracticeStageProgressResponse progress = stageProgress(fixture);
		assertThat(progress.marketBuySellCompleted()).isTrue();
		assertThat(progress.limitBuySellCompleted()).isFalse();
	}

	@Test
	void eachEntryCarriesTheOrderTypeOfItsOpeningBuy() {
		Fixture fixture = tutorialRunAtRumorStage("oco-entrytype");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();

		assertThat(practiceEntryComparisonService.findCurrentRunEntries(attempt, null))
			.singleElement()
			.satisfies(entry -> {
				assertThat(entry.entrySequence()).isEqualTo(1);
				assertThat(entry.buyOrderType()).isEqualTo("MARKET");
			});
	}

	@Test
	void aLimitRoundTripCompletesTheLimitStageAndTagsTheEntry() {
		Fixture fixture = tutorialRunAtRumorStage("limit-stage");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		clock.set(BASE_NOW.plusSeconds(2));
		sell(fixture);
		assertThat(stageProgress(fixture).marketBuySellCompleted()).isTrue();

		clock.set(BASE_NOW.plusSeconds(3));
		limitOrder(fixture, OrderSide.BUY, new BigDecimal("10000"));
		assertThat(stageProgress(fixture).limitBuySellCompleted()).isFalse();

		clock.set(BASE_NOW.plusSeconds(15));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(QUANTITY);
		assertThat(stageProgress(fixture).limitBuySellCompleted()).isFalse();

		PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		List<PracticeEntryResponse> entries = practiceEntryComparisonService.findCurrentRunEntries(attempt, null);
		assertThat(entries).hasSize(2);
		assertThat(entries.get(1).buyOrderType()).isEqualTo("LIMIT");

		reserve(fixture, "3", "5");
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(QUANTITY);
		limitOrder(fixture, OrderSide.SELL, new BigDecimal("9700"));
		clock.set(BASE_NOW.plusSeconds(18));
		chartService.tick(fixture.userId(), Market.CRYPTO);

		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);
		PracticeStageProgressResponse progress = stageProgress(fixture);
		assertThat(progress.limitBuySellCompleted()).isTrue();
		assertThat(progress.marketBuySellCompleted()).isTrue();
	}

	private void limitOrder(Fixture fixture, OrderSide side, BigDecimal limitPrice) {
		limitOrderService.createLimitOrder(
			fixture.userId(),
			"limit-stage-" + UUID.randomUUID(),
			new LimitOrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), side, QUANTITY, limitPrice));
	}

	private PracticeStageProgressResponse stageProgress(Fixture fixture) {
		return stageProgressCalculationService.calculate(
			attemptRepository.findById(fixture.attemptId()).orElseThrow());
	}

	@Test
	void manualMarketSellSucceedsWhileTheWholeQuantityIsReserved() {
		Fixture fixture = tutorialRunAtRumorStage("oco-manual");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		reserve(fixture, "3", "5");
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(QUANTITY);

		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(fixture.userId(), "manual-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.SELL, "MARKET", QUANTITY));

		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(onlyExitPlan(fixture).getStatus()).isEqualTo(ExitPlanStatus.CANCELLED);
	}

	@Test
	void aUserReservationIsCancellableAndTheCancelledRowStillBlocksTheSameEntry() {
		Fixture fixture = tutorialRunAtRumorStage("oco-cancel");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		reserve(fixture, "3", "5");
		Long planId = onlyExitPlan(fixture).getId();
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(QUANTITY);

		clock.set(BASE_NOW.plusSeconds(2));
		exitPlanService.cancel(fixture.userId(), planId);

		assertThat(onlyExitPlan(fixture).getStatus()).isEqualTo(ExitPlanStatus.CANCELLED);
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(BigDecimal.ZERO);

		assertThatThrownBy(() -> reserve(fixture, "5", "8"))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.EXIT_PLAN_ALREADY_EXISTS));

		assertThat(exitPlanReservationService.entryReservationSatisfied(
			attemptRepository.findById(fixture.attemptId()).orElseThrow())).isTrue();
	}

	@Test
	void anAutomaticReservationOnALegacyRunStaysUncancellable() {
		Fixture fixture = legacyTutorialRun("oco-legacy");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);

		ExitPlan automatic = onlyExitPlan(fixture);
		assertThat(automatic.getStatus()).isEqualTo(ExitPlanStatus.PENDING);

		assertThatThrownBy(() -> exitPlanService.cancel(fixture.userId(), automatic.getId()))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED));

		assertThat(onlyExitPlan(fixture).getStatus()).isEqualTo(ExitPlanStatus.PENDING);
		assertThat(reservedQuantity(fixture)).isEqualByComparingTo(QUANTITY);
	}

	@Test
	void aReservationFromAPastRunGenerationStaysUncancellable() {
		Fixture fixture = tutorialRunAtRumorStage("oco-pastrun");

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		reserve(fixture, "3", "5");
		Long planId = onlyExitPlan(fixture).getId();

		PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		ReflectionTestUtils.setField(attempt, "runNumber", 2L);
		attemptRepository.saveAndFlush(attempt);

		clock.set(BASE_NOW.plusSeconds(2));
		assertThatThrownBy(() -> exitPlanService.cancel(fixture.userId(), planId))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED));

		assertThat(onlyExitPlan(fixture).getStatus()).isEqualTo(ExitPlanStatus.PENDING);
	}

	private static BigDecimal expectedPrice(BigDecimal entryPrice, BigDecimal factor) {
		return entryPrice.multiply(factor).setScale(PRICE_SCALE, RoundingMode.HALF_UP);
	}

	private void reserve(Fixture fixture, String stopLossRate, String takeProfitRate) {
		exitPlanReservationService.create(
			fixture.userId(), Market.CRYPTO,
			ExitRates.of(new BigDecimal(stopLossRate), new BigDecimal(takeProfitRate)));
	}

	private void buy(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "oco-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.BUY, "MARKET", QUANTITY));
	}

	private PracticeRiskSnapshot latestSnapshot(Fixture fixture) {
		return riskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(fixture.attemptId(), 1L)
			.orElseThrow();
	}

	private ExitPlan onlyExitPlan(Fixture fixture) {
		List<ExitPlan> plans = exitPlanRepository
			.findByPracticeAttemptIdAndPracticeAttemptRunNumber(fixture.attemptId(), 1L);
		assertThat(plans).hasSize(1);
		return plans.get(0);
	}

	private BigDecimal reservedQuantity(Fixture fixture) {
		return holdingRepository.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.map(Holding::getReservedQuantity)
			.orElse(BigDecimal.ZERO);
	}

	private void sell(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "oco-sell-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.SELL, "MARKET", QUANTITY));
	}

	private Fixture newTutorialRun(String scenario) {
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
		return new Fixture(user.getId(), account.getId(), instrument.getId(),
			attemptRepository.findByUserIdAndMarket(user.getId(), Market.CRYPTO).orElseThrow().getId());
	}

	private Fixture legacyTutorialRun(String scenario) {
		Fixture fixture = newTutorialRun(scenario);
		PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		ReflectionTestUtils.setField(attempt, "generatorVersion", TutorialPriceGenerator.VERSION_1);
		ReflectionTestUtils.setField(attempt, "scenarioScriptId", null);
		attemptRepository.saveAndFlush(attempt);
		return fixture;
	}

	private Fixture tutorialRunAtRumorStage(String scenario) {
		Fixture fixture = newTutorialRun(scenario);
		PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		attempt.advanceScenarioScript(TutorialScenarioScriptId.CRYPTO_STORY_V1, BASE_NOW);
		attempt.startScenarioProgress("ACT2_RUMOR", ENTRY_PRICE, BASE_NOW);
		attemptRepository.saveAndFlush(attempt);
		return fixture;
	}

	private Fixture tutorialRunAtRumorStageAfterBothRoundTrips(String scenario) {
		Fixture fixture = newTutorialRun(scenario);
		PracticeAttempt onOrderBasics = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		onOrderBasics.startScenarioProgress("ORDER_BASICS", new BigDecimal("100000.00000000"), BASE_NOW);
		attemptRepository.saveAndFlush(onOrderBasics);

		clock.set(BASE_NOW.plusSeconds(1));
		buy(fixture);
		clock.set(BASE_NOW.plusSeconds(2));
		sell(fixture);

		clock.set(BASE_NOW.plusSeconds(3));
		limitOrder(fixture, OrderSide.BUY, new BigDecimal("95000"));
		clock.set(BASE_NOW.plusSeconds(33));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		clock.set(BASE_NOW.plusSeconds(39));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		clock.set(BASE_NOW.plusSeconds(40));
		limitOrder(fixture, OrderSide.SELL, new BigDecimal("90000"));
		clock.set(BASE_NOW.plusSeconds(42));
		chartService.tick(fixture.userId(), Market.CRYPTO);
		assertThat(tradeService.netFilledQuantity(fixture.attemptId(), 1L)).isEqualByComparingTo(BigDecimal.ZERO);

		clock.set(BASE_NOW.plusSeconds(43));
		scriptAdvanceService.advanceScript(fixture.userId(), Market.CRYPTO);

		PracticeAttempt attempt = attemptRepository.findById(fixture.attemptId()).orElseThrow();
		assertThat(attempt.scenarioScriptId()).isEqualTo(TutorialScenarioScriptId.CRYPTO_STORY_V1);
		attempt.startScenarioProgress("ACT2_RUMOR", ENTRY_PRICE, NARRATIVE_START);
		attemptRepository.saveAndFlush(attempt);
		return fixture;
	}

	private record Fixture(Long userId, Long accountId, Long instrumentId, Long attemptId) {
	}
}
