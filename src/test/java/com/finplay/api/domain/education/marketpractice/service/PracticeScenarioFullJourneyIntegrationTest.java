package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeEntryResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
@Transactional
class PracticeScenarioFullJourneyIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 20, 10, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("1.00000000");
	private static final int TICK_SECONDS = 30;
	private static final int MAX_TICKS = 60;
	private static final BigDecimal RUMOR_LOW = new BigDecimal("9750");
	private static final BigDecimal SCRIPT_FINAL_PRICE = new BigDecimal("7900.00000000");

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
	private InvestmentPracticeQueryService queryService;
	@Autowired
	private PracticeHoldingObservationService observationService;
	@Autowired
	private PracticeHoldingReflectionService reflectionService;
	@Autowired
	private PracticeExitPlanReservationService exitPlanReservationService;
	@Autowired
	private TestClock clock;

	private LocalDateTime now = BASE_NOW;

	@BeforeEach
	void setUp() {
		now = BASE_NOW;
		clock.set(now);
	}

	@Test
	void cautiousRunStopsInTheRumorThenReentersTakesProfitInActThreeAndCompletesWithTwoEntries() {
		Fixture fixture = tutorialRun("full-journey");
		setExitPreset(fixture, ExitPreset.CAUTIOUS);

		tick(fixture);
		PracticeTutorialChartResponse idle = tick(fixture);
		assertThat(idle.scenarioStage()).isEqualTo("IDLE_ENTRY");
		assertThat(idle.scenarioProgressing()).isFalse();
		assertThat(idle.causeStatus()).isEqualTo("NONE_KNOWN");
		assertThat(idle.revealedEvents()).isEmpty();

		buy(fixture);
		assertThat(plans(fixture)).isEmpty();
		reserve(fixture, "2", "3");
		PracticeRiskSnapshot firstEntry = snapshot(fixture, 1);
		assertThat(firstEntry.getExitPreset()).isEqualTo(ExitPreset.CAUTIOUS);

		PracticeTutorialChartResponse beforeReveal = tick(fixture, 9);
		assertThat(beforeReveal.scenarioStage()).isEqualTo("ACT1");
		assertThat(beforeReveal.scenarioProgressing()).isTrue();
		assertThat(beforeReveal.causeStatus()).isEqualTo("NONE_KNOWN");
		assertThat(beforeReveal.revealedEvents()).isEmpty();

		PracticeTutorialChartResponse afterReveal = tick(fixture, 9);
		assertThat(afterReveal.revealedEvents()).hasSize(1);
		assertThat(afterReveal.revealedEvents().get(0).stage()).isEqualTo("ACT1");
		assertThat(afterReveal.revealedEvents().get(0).headline()).startsWith("[연습]");
		assertThat(afterReveal.causeStatus()).isEqualTo("REVEALED");

		Long holdingId = holdingRepository
			.findByAccountIdAndInstrumentId(fixture.accountId(), fixture.instrumentId())
			.map(Holding::getId)
			.orElseThrow();
		observe(fixture, holdingId);
		tick(fixture, 70);
		observe(fixture, holdingId);
		tick(fixture, 70);
		observe(fixture, holdingId);

		tickUntil(fixture, () -> plans(fixture).get(0).getStatus() == ExitPlanStatus.FILLED_STOP_LOSS);
		InvestmentPracticeResponse afterStop = queryService.getProgress(fixture.userId(), Market.CRYPTO);
		assertThat(afterStop.entries()).hasSize(1);
		PracticeEntryResponse stopped = afterStop.entries().get(0);
		assertThat(stopped.sellCause()).isEqualTo("STOP_LOSS");
		assertThat(stopped.sellPrice()).isGreaterThanOrEqualTo(RUMOR_LOW);
		assertThat(afterStop.priceAfterSell()).isNotNull().isNotEqualByComparingTo(SCRIPT_FINAL_PRICE);

		PracticeTutorialChartResponse reentryIdle = tickUntil(
			fixture, () -> "IDLE_REENTRY".equals(latestChart(fixture).scenarioStage()));
		assertThat(reentryIdle.scenarioProgressing()).isFalse();
		assertThat(reentryIdle.causeStatus()).isEqualTo("NONE_KNOWN");
		assertThat(reentryIdle.revealedEvents()).hasSize(3);

		setExitPreset(fixture, ExitPreset.BALANCED);
		buy(fixture);
		reserve(fixture, "3", "5");
		assertThat(snapshot(fixture, 2).getExitPreset()).isEqualTo(ExitPreset.BALANCED);

		tickUntil(fixture, () -> plans(fixture).size() == 2
			&& plans(fixture).get(1).getStatus() == ExitPlanStatus.FILLED_TAKE_PROFIT);

		PracticeTutorialChartResponse finished = tickUntil(
			fixture, () -> "FINISHED".equals(latestChart(fixture).scenarioStage()));
		assertThat(finished.scenarioProgressing()).isFalse();
		assertThat(finished.revealedEvents()).hasSize(5);

		now = now.plusSeconds(TICK_SECONDS);
		clock.set(now);
		reflectionService.createReflection(fixture.userId(), new PracticeHoldingReflectionCreateRequest(
			holdingId, "루머에서 잘렸고 재진입해 익절했다. 끝까지 들고 있었으면 어땠을지 봤다."));

		InvestmentPracticeResponse completed = queryService.getProgress(fixture.userId(), Market.CRYPTO);
		assertThat(completed.status()).isEqualTo("COMPLETED");
		assertThat(completed.steps().get(2).status()).isEqualTo("COMPLETED");

		assertThat(completed.steps().get(3).evidence().tradeResult().sellCause()).isEqualTo("STOP_LOSS");
		assertThat(completed.entries()).hasSize(2);
		PracticeEntryResponse entryOne = completed.entries().get(0);
		PracticeEntryResponse entryTwo = completed.entries().get(1);
		assertThat(entryOne.entrySequence()).isEqualTo(1);
		assertThat(entryOne.exitPreset()).isEqualTo("CAUTIOUS");
		assertThat(entryOne.sellCause()).isEqualTo("STOP_LOSS");
		assertThat(entryTwo.entrySequence()).isEqualTo(2);
		assertThat(entryTwo.exitPreset()).isEqualTo("BALANCED");
		assertThat(entryTwo.sellCause()).isEqualTo("TAKE_PROFIT");
		assertThat(entryOne.buyOrderType()).isEqualTo("MARKET");
		assertThat(entryTwo.buyOrderType()).isEqualTo("MARKET");

		assertThat(completed.tutorialStageProgress().marketBuySellCompleted()).isFalse();
		assertThat(completed.tutorialStageProgress().limitBuySellCompleted()).isFalse();
		assertThat(completed.tutorialStageProgress().exitPresetSelected()).isTrue();

		assertThat(completed.priceAfterSell()).isEqualByComparingTo(SCRIPT_FINAL_PRICE);
		assertThat(entryOne.unrealizedPnlIfHeld()).isLessThan(entryOne.realizedPnl());
		assertThat(entryTwo.unrealizedPnlIfHeld()).isLessThan(entryTwo.realizedPnl());
		assertThat(completed.revealedEvents()).hasSize(5);
	}

	@Test
	void balancedRunSurvivesTheRumorAndOnlyStopsInTheConfirmedDive() {
		Fixture fixture = tutorialRun("balanced-branch");
		setExitPreset(fixture, ExitPreset.BALANCED);

		tick(fixture);
		buy(fixture);
		reserve(fixture, "3", "5");
		tickUntil(fixture, () -> plans(fixture).get(0).getStatus() == ExitPlanStatus.FILLED_STOP_LOSS);

		InvestmentPracticeResponse afterStop = queryService.getProgress(fixture.userId(), Market.CRYPTO);
		PracticeEntryResponse stopped = afterStop.entries().get(0);
		assertThat(stopped.sellCause()).isEqualTo("STOP_LOSS");
		assertThat(stopped.sellPrice()).isLessThan(RUMOR_LOW);
		assertThat(stopped.stopLossPrice()).isLessThan(RUMOR_LOW);
	}

	private void reserve(Fixture fixture, String stopLossRate, String takeProfitRate) {
		exitPlanReservationService.create(
			fixture.userId(), Market.CRYPTO,
			ExitRates.of(new BigDecimal(stopLossRate), new BigDecimal(takeProfitRate)));
	}

	private PracticeTutorialChartResponse tick(Fixture fixture) {
		return tick(fixture, TICK_SECONDS);
	}

	private PracticeTutorialChartResponse tick(Fixture fixture, int seconds) {
		now = now.plusSeconds(seconds);
		clock.set(now);
		return chartService.tick(fixture.userId(), Market.CRYPTO);
	}

	private PracticeTutorialChartResponse tickUntil(Fixture fixture, BooleanSupplier condition) {
		if (condition.getAsBoolean()) {
			return latestChart(fixture);
		}
		for (int attempt = 0; attempt < MAX_TICKS; attempt++) {
			PracticeTutorialChartResponse last = tick(fixture);
			if (condition.getAsBoolean()) {
				return last;
			}
		}
		throw new AssertionError("대본이 " + MAX_TICKS + "번의 tick 안에 조건에 도달하지 않았습니다.");
	}

	private PracticeTutorialChartResponse latestChart(Fixture fixture) {
		return chartService.getChart(fixture.userId(), Market.CRYPTO);
	}

	private void buy(Fixture fixture) {
		orderService.createOrder(fixture.userId(), "journey-buy-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, fixture.instrumentId(), OrderSide.BUY, "MARKET", QUANTITY));
	}

	private void setExitPreset(Fixture fixture, ExitPreset preset) {
		PracticeAttempt attempt = attemptRepository.findByUserIdAndMarket(fixture.userId(), Market.CRYPTO)
			.orElseThrow();
		attempt.selectExitPreset(preset, now);
		attemptRepository.saveAndFlush(attempt);
	}

	private void observe(Fixture fixture, Long holdingId) {
		observationService.createObservation(
			fixture.userId(), new PracticeHoldingObservationCreateRequest(holdingId));
	}

	private PracticeRiskSnapshot snapshot(Fixture fixture, int entrySequence) {
		return riskSnapshotRepository
			.findByAttemptIdAndRunNumberAndEntrySequence(fixture.attemptId(), 1L, entrySequence)
			.orElseThrow();
	}

	private List<ExitPlan> plans(Fixture fixture) {
		return exitPlanRepository
			.findByPracticeAttemptIdAndPracticeAttemptRunNumber(fixture.attemptId(), 1L)
			.stream()
			.sorted(Comparator.comparing(ExitPlan::getId))
			.toList();
	}

	private Fixture tutorialRun(String scenario) {
		String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		User user = userRepository.saveAndFlush(User.create(
			scenario + "-" + suffix + "@finplay.com", "hash", scenario + "-" + suffix, BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, BASE_NOW));
		Instrument instrument = instrumentRepository
			.findByMarketAndSymbol(Market.CRYPTO, "SANDBOX_COIN_1")
			.orElseThrow();

		attemptService.ensureAttempt(user.getId(), Market.CRYPTO);
		attemptService.selectInstrument(user.getId(), Market.CRYPTO, instrument.getId());
		PracticeAttempt attempt = attemptRepository.findByUserIdAndMarket(user.getId(), Market.CRYPTO).orElseThrow();
		attempt.advanceScenarioScript(
			com.finplay.api.domain.market.entity.TutorialScenarioScriptId.CRYPTO_STORY_V1, BASE_NOW);
		attemptRepository.saveAndFlush(attempt);
		return new Fixture(user.getId(), account.getId(), instrument.getId(), attempt.getId());
	}

	private record Fixture(Long userId, Long accountId, Long instrumentId, Long attemptId) {
	}
}
