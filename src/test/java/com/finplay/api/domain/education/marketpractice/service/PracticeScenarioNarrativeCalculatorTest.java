package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.market.service.TutorialScenarioScript;
import com.finplay.api.domain.market.service.TutorialScenarioScriptLoader;
import com.finplay.api.domain.market.service.TutorialScenarioStage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class PracticeScenarioNarrativeCalculatorTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 12, 0);
	private static final int SECONDS_PER_VIRTUAL_MINUTE = PracticeAttemptCanonicalPriceService.SECONDS_PER_VIRTUAL_MINUTE;
	private final TutorialScenarioScript script = new TutorialScenarioScriptLoader(new ObjectMapper())
		.script(TutorialScenarioScriptId.CRYPTO_STORY_V1);

	@Test
	void unstartedCursorReadsAsTheFirstIdleStage() {
		PracticeScenarioNarrativeDto narrative = PracticeScenarioNarrativeCalculator.calculate(attempt(), script);

		assertThat(narrative.scenarioStage()).isEqualTo("IDLE_ENTRY");
		assertThat(narrative.scenarioProgressing()).isFalse();
		assertThat(narrative.causeStatus()).isEqualTo("NONE_KNOWN");
		assertThat(narrative.revealedEvents()).isEmpty();
	}

	@Test
	void eventStaysHiddenUntilItsRevealMinuteEvenAfterItsPriceImpactStarted() {
		int revealMinute = revealMinuteOf("ACT1_RISE");

		PracticeScenarioNarrativeDto justBefore = calculateAt("ACT1_RISE", revealMinute - 1);
		assertThat(justBefore.revealedEvents()).isEmpty();
		assertThat(justBefore.causeStatus()).isEqualTo("NONE_KNOWN");

		PracticeScenarioNarrativeDto atReveal = calculateAt("ACT1_RISE", revealMinute);
		assertThat(atReveal.revealedEvents()).hasSize(1);
		assertThat(atReveal.revealedEvents().get(0).stage()).isEqualTo("ACT1");
		assertThat(atReveal.revealedEvents().get(0).headline()).startsWith("[연습]");
		assertThat(atReveal.causeStatus()).isEqualTo("REVEALED");
	}

	@Test
	void fakeoutStageReportsNoKnownCauseEvenThoughEarlierActTwoEventsAreRevealed() {
		PracticeScenarioNarrativeDto narrative = calculateAt("ACT2_FAKEOUT", 0);

		assertThat(narrative.scenarioStage()).isEqualTo("ACT2");
		assertThat(narrative.causeStatus()).isEqualTo("NONE_KNOWN");
		assertThat(narrative.revealedEvents()).extracting("stage").containsExactly("ACT1", "ACT2");
	}

	@Test
	void reentryIdleStageKeepsEarlierEventsAndReportsNotProgressing() {
		PracticeScenarioNarrativeDto narrative = calculateAt("IDLE_REENTRY", 3);

		assertThat(narrative.scenarioStage()).isEqualTo("IDLE_REENTRY");
		assertThat(narrative.scenarioProgressing()).isFalse();
		assertThat(narrative.causeStatus()).isEqualTo("NONE_KNOWN");
		assertThat(narrative.revealedEvents()).extracting("stage").containsExactly("ACT1", "ACT2", "ACT2");
	}

	@Test
	void lastProgressStageIsProgressingUntilTheScriptEnds() {
		PracticeScenarioNarrativeDto narrative = calculateAt("ACT4_CRASH", 5);

		assertThat(narrative.scenarioStage()).isEqualTo("ACT4");
		assertThat(narrative.scenarioProgressing()).isTrue();
	}

	@Test
	void exhaustedLastStageReportsFinishedWithEveryEventRevealed() {
		TutorialScenarioStage last = script.stages().get(script.stages().size() - 1);

		PracticeScenarioNarrativeDto narrative = calculateAt(last.id(), last.minutes());

		assertThat(narrative.scenarioStage()).isEqualTo("FINISHED");
		assertThat(narrative.scenarioProgressing()).isFalse();
		assertThat(narrative.revealedEvents()).hasSize(script.events().size());
		assertThat(narrative.causeStatus()).isEqualTo("REVEALED");
	}

	private PracticeScenarioNarrativeDto calculateAt(String stageId, int minute) {
		PracticeAttempt attempt = attempt();
		attempt.startScenarioProgress(stageId, BigDecimal.TEN, NOW);
		attempt.moveScenarioCursor(stageId, (long)minute * SECONDS_PER_VIRTUAL_MINUTE);
		return PracticeScenarioNarrativeCalculator.calculate(attempt, script);
	}

	private int revealMinuteOf(String stageId) {
		return script.events().stream()
			.filter(event -> event.stageId().equals(stageId))
			.findFirst()
			.orElseThrow()
			.revealMinute();
	}

	private static PracticeAttempt attempt() {
		PracticeAttempt attempt = PracticeAttempt.create(11L, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", 5L);
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "SANDBOX_COIN_1", "알파코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 21L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(
			instrument, NOW, NOW.toLocalDate(), 1L, TutorialPriceGenerator.VERSION_2, null, NOW);
		return attempt;
	}
}
