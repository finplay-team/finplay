package com.finplay.api.domain.education.marketpractice.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.education.marketpractice.service.ReferencePriceCalculator;
import com.finplay.api.domain.education.marketpractice.service.ReferencePriceLines;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.service.TutorialScenarioEvent;
import com.finplay.api.domain.market.service.TutorialScenarioScript;
import com.finplay.api.domain.market.service.TutorialScenarioScriptLoader;
import com.finplay.api.domain.market.service.TutorialScenarioStage;
import com.finplay.api.domain.market.service.TutorialScenarioStageKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ExitPresetScenarioReachabilityTest {

	private final TutorialScenarioScript script = new TutorialScenarioScriptLoader(new ObjectMapper())
		.script(TutorialScenarioScriptId.CRYPTO_STORY_V1);
	private final ReferencePriceCalculator calculator = new ReferencePriceCalculator();

	@Test
	void firstActNeverReachesTheNarrowestTakeProfitLine() {
		forEachPreset(preset -> assertThat(takeProfitLow(preset))
			.as("%s 익절선", preset)
			.isGreaterThan(high("ACT1_RISE")));
	}

	@Test
	void rumorLowSeparatesCautiousFromTheOtherPresets() {
		BigDecimal rumorLow = low("ACT2_RUMOR");
		assertThat(stopLossLow(ExitPreset.CAUTIOUS)).isGreaterThan(rumorLow);
		assertThat(stopLossHigh(ExitPreset.BALANCED)).isLessThan(rumorLow);
		assertThat(stopLossHigh(ExitPreset.RELAXED)).isLessThan(rumorLow);
		assertThat(stopLossHigh(ExitPreset.BALANCED)).isLessThan(stopLossLow(ExitPreset.CAUTIOUS));
		assertThat(stopLossHigh(ExitPreset.RELAXED)).isLessThan(stopLossLow(ExitPreset.BALANCED));
	}

	@Test
	void cautiousStopsOutAfterTheRumorHeadlineOpens() {
		List<BigDecimal> ratios = script.stage("ACT2_RUMOR").ratios();
		BigDecimal widestCautiousLine = stopLossHigh(ExitPreset.CAUTIOUS);
		int stopOutMinute = 0;
		while (stopOutMinute < ratios.size() && ratios.get(stopOutMinute).compareTo(widestCautiousLine) > 0) {
			stopOutMinute++;
		}
		assertThat(stopOutMinute).as("CAUTIOUS 손절 분").isLessThan(ratios.size());

		TutorialScenarioEvent rumor = script.events().stream()
			.filter(event -> event.stageId().equals("ACT2_RUMOR"))
			.findFirst()
			.orElseThrow();
		assertThat(stopOutMinute).isGreaterThanOrEqualTo(rumor.revealMinute());
	}

	@Test
	void fakeoutReboundClimbsBackAboveEveryStopLineButReachesNoTakeProfitLine() {
		forEachPreset(preset -> {
			assertThat(stopLossHigh(preset)).as("%s 손절선", preset).isLessThan(high("ACT2_FAKEOUT"));
			assertThat(takeProfitLow(preset)).as("%s 익절선", preset).isGreaterThan(high("ACT2_FAKEOUT"));
		});
	}

	@Test
	void confirmedDropStopsOutEveryPreset() {
		forEachPreset(preset -> assertThat(stopLossLow(preset))
			.as("%s 손절선", preset)
			.isGreaterThan(low("ACT2_CONFIRM")));
	}

	@Test
	void thirdActTakesProfitAndFourthActStopsOutEveryReentry() {
		forEachPreset(preset -> {
			assertThat(reentryTakeProfitHigh(preset))
				.as("%s 재진입 익절선", preset)
				.isLessThan(high("ACT3_REBOUND"));
			assertThat(reentryStopLossLow(preset))
				.as("%s 재진입 손절선", preset)
				.isGreaterThan(low("ACT4_CRASH"));
		});
	}

	@Test
	void fourthActFallsFurtherThanTheUpsideMissedByTakingProfit() {
		BigDecimal crashDrop = BigDecimal.ONE.subtract(
			low("ACT4_CRASH").divide(high("ACT4_CRASH"), 8, RoundingMode.HALF_UP));

		forEachPreset(preset -> {
			BigDecimal takeProfitLine = takeProfitLine(preset, reentryLow());
			BigDecimal missedUpside = high("ACT3_REBOUND")
				.subtract(takeProfitLine)
				.divide(takeProfitLine, 8, RoundingMode.HALF_UP);

			assertThat(missedUpside).as("%s 익절 후 놓친 상승분", preset).isLessThan(crashDrop);
		});
	}


	private static final BigDecimal RATE_STEP = new BigDecimal("0.1");

	@Test
	void everyAllowedRatePairStopsOutOnTheFirstEntryBeforeReachingTakeProfit() {
		List<BigDecimal> path = pathAfterEntryIn("IDLE_ENTRY");
		forEachRatePair((stopLossRate, takeProfitRate) -> {
			for (BigDecimal entryRatio : script.stage("IDLE_ENTRY").ratios()) {
				assertThat(firstLineReached(entryRatio, stopLossRate, takeProfitRate, path))
					.as("1막 진입 배율 %s · 손절 %s%% · 익절 %s%%", entryRatio, stopLossRate, takeProfitRate)
					.isEqualTo(Reached.STOP_LOSS);
			}
		});
	}

	@Test
	void everyAllowedRatePairTakesProfitOnTheReentryBeforeReachingStopLoss() {
		List<BigDecimal> path = pathAfterEntryIn("IDLE_REENTRY");
		forEachRatePair((stopLossRate, takeProfitRate) -> {
			for (BigDecimal entryRatio : script.stage("IDLE_REENTRY").ratios()) {
				assertThat(firstLineReached(entryRatio, stopLossRate, takeProfitRate, path))
					.as("재진입 배율 %s · 손절 %s%% · 익절 %s%%", entryRatio, stopLossRate, takeProfitRate)
					.isEqualTo(Reached.TAKE_PROFIT);
			}
		});
	}

	private enum Reached {
		STOP_LOSS, TAKE_PROFIT, NEITHER
	}

	private Reached firstLineReached(
		BigDecimal entryRatio, BigDecimal stopLossRate, BigDecimal takeProfitRate, List<BigDecimal> path) {
		ReferencePriceLines lines = calculator.calculateFromRates(
			entryRatio, ExitRates.of(stopLossRate, takeProfitRate));
		for (BigDecimal ratio : path) {
			if (ratio.compareTo(lines.referenceStopLossPrice()) <= 0) {
				return Reached.STOP_LOSS;
			}
			if (ratio.compareTo(lines.referenceTakeProfitPrice()) >= 0) {
				return Reached.TAKE_PROFIT;
			}
		}
		return Reached.NEITHER;
	}

	private List<BigDecimal> pathAfterEntryIn(String waitStageId) {
		List<TutorialScenarioStage> stages = script.stages();
		int start = 0;
		while (!stages.get(start).id().equals(waitStageId)) {
			start++;
		}
		while (stages.get(start).kind() != TutorialScenarioStageKind.PROGRESS) {
			start++;
		}
		List<BigDecimal> path = new ArrayList<>();
		for (int index = start; index < stages.size(); index++) {
			path.addAll(stages.get(index).ratios());
		}
		return List.copyOf(path);
	}

	private void forEachRatePair(RatePairAssertion assertion) {
		for (BigDecimal stopLossRate = ExitRates.STOP_LOSS_MIN; stopLossRate
			.compareTo(ExitRates.STOP_LOSS_MAX) <= 0; stopLossRate = stopLossRate.add(RATE_STEP)) {
			for (BigDecimal takeProfitRate = ExitRates.TAKE_PROFIT_MIN; takeProfitRate
				.compareTo(ExitRates.TAKE_PROFIT_MAX) <= 0; takeProfitRate = takeProfitRate.add(RATE_STEP)) {
				assertion.accept(stopLossRate, takeProfitRate);
			}
		}
	}

	@FunctionalInterface
	private interface RatePairAssertion {
		void accept(BigDecimal stopLossRate, BigDecimal takeProfitRate);
	}

	private void forEachPreset(Consumer<ExitPreset> assertion) {
		for (ExitPreset preset : ExitPreset.values()) {
			assertion.accept(preset);
		}
	}

	private BigDecimal stopLossLine(ExitPreset preset, BigDecimal entryRatio) {
		return calculator.calculateFromPreset(entryRatio, preset).referenceStopLossPrice();
	}

	private BigDecimal takeProfitLine(ExitPreset preset, BigDecimal entryRatio) {
		return calculator.calculateFromPreset(entryRatio, preset).referenceTakeProfitPrice();
	}

	private BigDecimal stopLossLow(ExitPreset preset) {
		return stopLossLine(preset, low("IDLE_ENTRY"));
	}

	private BigDecimal stopLossHigh(ExitPreset preset) {
		return stopLossLine(preset, high("IDLE_ENTRY"));
	}

	private BigDecimal takeProfitLow(ExitPreset preset) {
		return takeProfitLine(preset, low("IDLE_ENTRY"));
	}

	private BigDecimal reentryLow() {
		return low("IDLE_REENTRY");
	}

	private BigDecimal reentryStopLossLow(ExitPreset preset) {
		return stopLossLine(preset, reentryLow());
	}

	private BigDecimal reentryTakeProfitHigh(ExitPreset preset) {
		return takeProfitLine(preset, high("IDLE_REENTRY"));
	}

	private BigDecimal low(String stageId) {
		return script.stage(stageId).ratios().stream()
			.min(BigDecimal::compareTo)
			.orElseThrow();
	}

	private BigDecimal high(String stageId) {
		return script.stage(stageId).ratios().stream()
			.max(BigDecimal::compareTo)
			.orElseThrow();
	}
}
