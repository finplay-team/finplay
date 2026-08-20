// 프리셋 세 개의 손절·익절선이 041 대본에서 실제로 닿는지를 대본 파일과 상수로 판정한다 (EXITPRESET-010).
package com.finplay.api.education.marketpractice.domain;

import com.finplay.api.market.domain.TutorialScenarioScriptId;
import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.education.marketpractice.service.ReferencePriceCalculator;
import com.finplay.api.market.service.TutorialScenarioEvent;
import com.finplay.api.market.service.TutorialScenarioScript;
import com.finplay.api.market.service.TutorialScenarioScriptLoader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 041 plan §프리셋 도달 조건 검증의 부등식 전부를 여기서 판정한다. 프리셋 수치나 대본 배율 중 하나만 바뀌어도
 * 깨지도록 <b>양쪽을 리터럴 없이 읽는다</b> — 프리셋은 {@link ExitPreset} 상수에서, 배율은 대본 파일에서.
 *
 * <p><b>같은 조건을 두 곳에서 검사하지 않는다.</b> 041 1번이 이 부등식을 {@code TutorialScenarioScriptIntegrityTest}에
 * 프리셋 리터럴로 넣어 두고 "042의 상수가 들어오면 옮긴다"고 적어 뒀다. 판정 주체가 프리셋이므로
 * {@code market} 패키지 테스트가 {@code education}의 열거형을 참조하는 대신 이쪽으로 옮겨 왔고, 저쪽에는
 * 대본 내부 성질(구간 배분·극값·사건 배치·무귀속 &gt; 귀속)만 남겼다.
 *
 * <p>기준선을 직접 곱하지 않고 {@link ReferencePriceCalculator}로 계산하는 이유는, 실제 사용자가 겪는 선이
 * 그 계산기가 만든 선이기 때문이다. 배율을 진입가 자리에 넣으면 결과도 같은 배율 단위가 된다.
 */
class ExitPresetScenarioReachabilityTest {

	private final TutorialScenarioScript script = new TutorialScenarioScriptLoader(new ObjectMapper())
		.script(TutorialScenarioScriptId.CRYPTO_STORY_V1);
	private final ReferencePriceCalculator calculator = new ReferencePriceCalculator();

	@Test
	void firstActNeverReachesTheNarrowestTakeProfitLine() {
		// 1막에서 익절이 터지면 2막 손절 학습을 통째로 못 한다. 가장 좁은 익절률이 +3%인 이상
		// 1막은 3%보다 크게 오를 수 없다 — 1막 고점 1.018은 이 부등식의 결과다.
		forEachPreset(preset -> assertThat(takeProfitLow(preset))
			.as("%s 익절선", preset)
			.isGreaterThan(high("ACT1_RISE")));
	}

	@Test
	void rumorLowSeparatesCautiousFromTheOtherPresets() {
		// 좁게 잡은 사용자만 소문 단계에서 털려 나간다. 세 손절선 구간이 겹치지 않아야 진입가 편차가 아니라
		// 고른 프리셋이 결과를 정한다 — 여유가 0.3%p뿐이라 이 대본에서 가장 깨지기 쉬운 조건이다.
		// 루머 저점(0.975)도 리터럴로 적지 않고 대본에서 읽는다 — 적어 두면 대본이 바뀐 뒤에도 옛 값 기준으로
		// 통과해 버린다. 그 저점이 계획대로 0.975인지는 041의 대본 극값 테스트가 본다.
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
		// 손절선에 닿는 분을 못 찾아도 인덱스를 넘겨 죽지 않게 한다 — 그 경우는 "루머에서 CAUTIOUS가 털린다"가
		// 무너진 것이므로 스택트레이스가 아니라 단언 실패로 보여야 원인이 바로 읽힌다.
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

	// 이름을 내용과 맞춘다 — 첫 단언은 "아무도 구제되지 않는다"가 아니라 반등 고점이 세 손절선 위로 올라온다는
	// 것이고(속임수처럼 보이는 이유), 실제로 아무 일도 일어나지 않게 하는 것은 두 번째 단언이다.
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

	// SCENARIO-004·006 상한 — 4막 하락폭이 익절한 사용자가 놓친 상승분보다 커야 "익절이 옳았다"가 결과로
	// 증명된다. 이 조건이 없으면 4막만 얕게 손보는 수정이 아무 테스트도 깨지 않고 통과한다
	// (041 plan §잔여 위험: "4막을 얕게 만드는 수정은 단독으로 하면 안 된다").
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

	/** 1막 진입자의 손절선 구간 하단(가장 낮은 진입 배율에서 나온 선). */
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
