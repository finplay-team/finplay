// 저작된 CRYPTO 대본이 041 plan의 구간 배분·프리셋 도달 부등식·사건 배치를 만족하는지 파일을 읽어 판정한다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

// 이 테스트는 대본 배율과 042 프리셋 수치가 조용히 어긋나는 것을 막는 장치다. 둘 중 하나만 바뀌어도 깨진다.
// 프리셋 값은 042가 상수로 확정하기 전이라 여기서는 plan §프리셋 도달 조건의 표를 리터럴로 적는다 —
// 042의 프리셋 상수가 들어오면 그 상수를 읽도록 바꾼다(tasks §교차 순서 4번).
class TutorialScenarioScriptIntegrityTest {

	private static final BigDecimal RUMOR_LOW = new BigDecimal("0.975");
	private static final Map<String, BigDecimal[]> PRESETS = Map.of(
		"CAUTIOUS", new BigDecimal[] {new BigDecimal("0.02"), new BigDecimal("0.03")},
		"BALANCED", new BigDecimal[] {new BigDecimal("0.03"), new BigDecimal("0.05")},
		"RELAXED", new BigDecimal[] {new BigDecimal("0.05"), new BigDecimal("0.08")});

	private final TutorialScenarioScript script = new TutorialScenarioScriptLoader(new ObjectMapper())
		.script(Market.CRYPTO);

	@Test
	void stagesFollowPlannedOrderAndLengths() {
		assertThat(script.stages()).extracting(TutorialScenarioStage::id).containsExactly(
			"IDLE_ENTRY",
			"ACT1_RISE",
			"ACT2_RUMOR",
			"ACT2_FAKEOUT",
			"ACT2_CONFIRM",
			"IDLE_REENTRY",
			"ACT3_REBOUND",
			"ACT4_CRASH");
		assertThat(script.stages())
			.extracting(TutorialScenarioStage::minutes)
			.containsExactly(20, 15, 8, 5, 12, 15, 25, 20);
		assertThat(script.stages())
			.extracting(TutorialScenarioStage::act)
			.containsExactly(null, "ACT1", "ACT2", "ACT2", "ACT2", null, "ACT3", "ACT4");
		assertThat(loopStageIds()).containsExactly("IDLE_ENTRY", "IDLE_REENTRY");
		assertThat(progressMinutes()).isEqualTo(85);
		assertThat(script.stages().stream()
			.mapToInt(stage -> stage.ratios().size())
			.sum())
			.isEqualTo(120);
	}

	@Test
	void stageExtremesMatchPlannedRatios() {
		assertStageBand("IDLE_ENTRY", "0.998", "1.002");
		assertStageBand("ACT1_RISE", "1.000", "1.018");
		assertStageBand("ACT2_RUMOR", "0.975", "1.018");
		assertStageBand("ACT2_FAKEOUT", "0.975", "0.995");
		assertStageBand("ACT2_CONFIRM", "0.870", "0.995");
		assertStageBand("IDLE_REENTRY", "0.868", "0.872");
		assertStageBand("ACT3_REBOUND", "0.870", "1.010");
		assertStageBand("ACT4_CRASH", "0.790", "1.010");
	}

	@Test
	void firstActNeverReachesTheNarrowestTakeProfitLine() {
		// 1막에서 익절이 터지면 2막 손절 학습을 통째로 못 한다. 가장 좁은 익절률이 +3%인 이상
		// 1막은 3%보다 크게 오를 수 없다 — 1막 고점 1.018은 이 부등식의 결과다.
		PRESETS.forEach((name, rates) -> assertThat(entryLow().multiply(BigDecimal.ONE.add(rates[1])))
			.as("%s 익절선", name)
			.isGreaterThan(high("ACT1_RISE")));
	}

	@Test
	void rumorLowSeparatesCautiousFromTheOtherPresets() {
		// 좁게 잡은 사용자만 소문 단계에서 털려 나간다. 세 손절선 구간이 겹치지 않아야 진입가 편차가 아니라
		// 고른 프리셋이 결과를 정한다 — 여유가 0.3%p뿐이라 이 대본에서 가장 깨지기 쉬운 조건이다.
		assertThat(stopLossLow("CAUTIOUS")).isGreaterThan(RUMOR_LOW);
		assertThat(stopLossHigh("BALANCED")).isLessThan(RUMOR_LOW);
		assertThat(stopLossHigh("RELAXED")).isLessThan(RUMOR_LOW);
		assertThat(stopLossHigh("BALANCED")).isLessThan(stopLossLow("CAUTIOUS"));
		assertThat(stopLossHigh("RELAXED")).isLessThan(stopLossLow("BALANCED"));
		assertThat(low("ACT2_RUMOR")).isEqualByComparingTo(RUMOR_LOW);
	}

	@Test
	void fakeoutReboundRescuesNobodyAndTriggersNoTakeProfit() {
		PRESETS.forEach((name, rates) -> {
			assertThat(stopLossHigh(name)).as("%s 손절선", name).isLessThan(high("ACT2_FAKEOUT"));
			assertThat(entryLow().multiply(BigDecimal.ONE.add(rates[1])))
				.as("%s 익절선", name)
				.isGreaterThan(high("ACT2_FAKEOUT"));
		});
	}

	@Test
	void confirmedDropStopsOutEveryPreset() {
		PRESETS.keySet()
			.forEach(name -> assertThat(stopLossLow(name))
				.as("%s 손절선", name)
				.isGreaterThan(low("ACT2_CONFIRM")));
	}

	@Test
	void thirdActTakesProfitAndFourthActStopsOutEveryReentry() {
		PRESETS.forEach((name, rates) -> {
			assertThat(reentryHigh().multiply(BigDecimal.ONE.add(rates[1])))
				.as("%s 재진입 익절선", name)
				.isLessThan(high("ACT3_REBOUND"));
			assertThat(reentryLow().multiply(BigDecimal.ONE.subtract(rates[0])))
				.as("%s 재진입 손절선", name)
				.isGreaterThan(low("ACT4_CRASH"));
		});
	}

	// SCENARIO-004·006 상한 — 4막 하락폭이 익절한 사용자가 놓친 상승분보다 커야 "익절이 옳았다"가 결과로
	// 증명된다. 이 조건이 없으면 4막만 얕게 손보는 수정이 아무 테스트도 깨지 않고 통과한다
	// (plan §잔여 위험: "4막을 얕게 만드는 수정은 단독으로 하면 안 된다").
	@Test
	void fourthActFallsFurtherThanTheUpsideMissedByTakingProfit() {
		BigDecimal crashDrop = BigDecimal.ONE.subtract(
			low("ACT4_CRASH").divide(high("ACT4_CRASH"), 8, java.math.RoundingMode.HALF_UP));

		PRESETS.forEach((name, rates) -> {
			BigDecimal takeProfitPrice = reentryLow().multiply(BigDecimal.ONE.add(rates[1]));
			BigDecimal missedUpside = high("ACT3_REBOUND")
				.subtract(takeProfitPrice)
				.divide(takeProfitPrice, 8, java.math.RoundingMode.HALF_UP);

			assertThat(missedUpside).as("%s 익절 후 놓친 상승분", name).isLessThan(crashDrop);
		});
	}

	@Test
	void unattributedMinutesOutnumberAttributedMinutes() {
		int attributed = script.events().stream()
			.mapToInt(TutorialScenarioEvent::impactMinutes)
			.sum();
		assertThat(attributed).isEqualTo(30);
		assertThat(progressMinutes() - attributed).isGreaterThan(attributed);
	}

	@Test
	void everyEventOpensInsideItsOwnStageAndFakeoutHasNone() {
		assertThat(script.events()).hasSize(5);
		assertThat(script.events())
			.extracting(TutorialScenarioEvent::stageId)
			.doesNotContain("ACT2_FAKEOUT", "IDLE_ENTRY", "IDLE_REENTRY");
		// SCENARIO-006c — 대표 경로가 구간을 다 지나기 전에 공개 시점이 와야 한다. 배율은 도달 부등식이
		// 잠그고 있으므로 이 조건은 영향 시작 시점과 공개 지연으로만 맞춘다.
		assertThat(script.events()).allSatisfy(event -> assertThat(event.revealMinute())
			.as("%s 공개 시점", event.stageId())
			.isLessThan(script.stage(event.stageId()).minutes()));
	}

	@Test
	void cautiousStopsOutAfterTheRumorHeadlineOpens() {
		List<BigDecimal> ratios = script.stage("ACT2_RUMOR").ratios();
		BigDecimal widestCautiousLine = stopLossHigh("CAUTIOUS");
		int stopOutMinute = 0;
		while (ratios.get(stopOutMinute).compareTo(widestCautiousLine) > 0) {
			stopOutMinute++;
		}
		TutorialScenarioEvent rumor = script.events().stream()
			.filter(event -> event.stageId().equals("ACT2_RUMOR"))
			.findFirst()
			.orElseThrow();
		assertThat(stopOutMinute).isGreaterThanOrEqualTo(rumor.revealMinute());
	}

	@Test
	void headlinesAreMarkedAsPractice() {
		assertThat(script.events())
			.allSatisfy(event -> assertThat(event.headline()).startsWith("[연습] "));
	}

	private List<String> loopStageIds() {
		return script.stages().stream()
			.filter(stage -> stage.kind() == TutorialScenarioStageKind.LOOP)
			.map(TutorialScenarioStage::id)
			.toList();
	}

	private int progressMinutes() {
		return script.stages().stream()
			.filter(stage -> stage.kind() == TutorialScenarioStageKind.PROGRESS)
			.mapToInt(TutorialScenarioStage::minutes)
			.sum();
	}

	private void assertStageBand(String stageId, String lowest, String highest) {
		assertThat(low(stageId)).as("%s 저점", stageId).isEqualByComparingTo(lowest);
		assertThat(high(stageId)).as("%s 고점", stageId).isEqualByComparingTo(highest);
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

	private BigDecimal entryLow() {
		return low("IDLE_ENTRY");
	}

	private BigDecimal entryHigh() {
		return high("IDLE_ENTRY");
	}

	private BigDecimal reentryLow() {
		return low("IDLE_REENTRY");
	}

	private BigDecimal reentryHigh() {
		return high("IDLE_REENTRY");
	}

	private BigDecimal stopLossLow(String preset) {
		return entryLow().multiply(BigDecimal.ONE.subtract(PRESETS.get(preset)[0]));
	}

	private BigDecimal stopLossHigh(String preset) {
		return entryHigh().multiply(BigDecimal.ONE.subtract(PRESETS.get(preset)[0]));
	}
}
