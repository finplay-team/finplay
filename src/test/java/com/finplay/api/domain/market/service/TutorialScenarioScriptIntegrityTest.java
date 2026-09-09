package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TutorialScenarioScriptIntegrityTest {

	private final TutorialScenarioScript script = new TutorialScenarioScriptLoader(new ObjectMapper())
		.script(TutorialScenarioScriptId.CRYPTO_STORY_V1);

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
		assertThat(script.events()).allSatisfy(event -> assertThat(event.revealMinute())
			.as("%s 공개 시점", event.stageId())
			.isLessThan(script.stage(event.stageId()).minutes()));
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
}
