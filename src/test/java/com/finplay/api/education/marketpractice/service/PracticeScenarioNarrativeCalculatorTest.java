// 대본 커서에서 파생하는 막·진행 여부·원인 상태와 사건 공개 게이트를 실제 대본으로 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.TutorialPriceGenerator;
import com.finplay.api.market.service.TutorialScenarioScript;
import com.finplay.api.market.service.TutorialScenarioScriptId;
import com.finplay.api.market.service.TutorialScenarioScriptLoader;
import com.finplay.api.market.service.TutorialScenarioStage;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

// 픽스처 대본을 만들지 않고 배포되는 대본 파일을 그대로 읽는다 — 공개 지연·구간 배치를 손보면 이 테스트가
// 함께 반응해야 한다(대본 배율을 고치면 TutorialScenarioScriptIntegrityTest와 함께 본다).
class PracticeScenarioNarrativeCalculatorTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 12, 0);
	private static final int SECONDS_PER_VIRTUAL_MINUTE = PracticeAttemptCanonicalPriceService.SECONDS_PER_VIRTUAL_MINUTE;
	private final TutorialScenarioScript script = new TutorialScenarioScriptLoader(new ObjectMapper())
		.script(TutorialScenarioScriptId.CRYPTO_STORY_V1);

	// 커서가 비어 있으면 미시작이다 — 첫 tick 전의 조회도 첫 구간 0분으로 답해야 화면이 비지 않는다.
	@Test
	void unstartedCursorReadsAsTheFirstIdleStage() {
		PracticeScenarioNarrativeDto narrative = PracticeScenarioNarrativeCalculator.calculate(attempt(), script);

		assertThat(narrative.scenarioStage()).isEqualTo("IDLE_ENTRY");
		assertThat(narrative.scenarioProgressing()).isFalse();
		assertThat(narrative.causeStatus()).isEqualTo("NONE_KNOWN");
		assertThat(narrative.revealedEvents()).isEmpty();
	}

	// SCENARIO-015 — 영향이 이미 가격에 들어간 뒤에도 공개 분에 닿기 전에는 어떤 형태로도 나오지 않는다.
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

	// **가장 중요한 회귀.** 2막-b 속임수 반등에는 사건이 없다(plan §사건 배치). 원인 상태를 막(ACT2) 단위로
	// 판정하면 앞 구간 루머가 열린 뒤 이 구간까지 REVEALED가 되어 "원인 없는 변동"이라는 설계가 사라진다.
	@Test
	void fakeoutStageReportsNoKnownCauseEvenThoughEarlierActTwoEventsAreRevealed() {
		PracticeScenarioNarrativeDto narrative = calculateAt("ACT2_FAKEOUT", 0);

		assertThat(narrative.scenarioStage()).isEqualTo("ACT2");
		assertThat(narrative.causeStatus()).isEqualTo("NONE_KNOWN");
		assertThat(narrative.revealedEvents()).extracting("stage").containsExactly("ACT1", "ACT2");
	}

	// 대기 구간은 보유가 없어 대본이 멈춘 자리다 — 지나온 구간의 사건은 계속 열려 있어야 재진입한 사용자가
	// 이야기를 잃지 않는다(SCENARIO-019a와 같은 취지).
	@Test
	void reentryIdleStageKeepsEarlierEventsAndReportsNotProgressing() {
		PracticeScenarioNarrativeDto narrative = calculateAt("IDLE_REENTRY", 3);

		assertThat(narrative.scenarioStage()).isEqualTo("IDLE_REENTRY");
		assertThat(narrative.scenarioProgressing()).isFalse();
		assertThat(narrative.causeStatus()).isEqualTo("NONE_KNOWN");
		assertThat(narrative.revealedEvents()).extracting("stage").containsExactly("ACT1", "ACT2", "ACT2");
	}

	// 미보유로 4막을 관전 중인 사용자도 진행 중이다 — scenarioProgressing은 보유 여부와 무관하다.
	@Test
	void lastProgressStageIsProgressingUntilTheScriptEnds() {
		PracticeScenarioNarrativeDto narrative = calculateAt("ACT4_CRASH", 5);

		assertThat(narrative.scenarioStage()).isEqualTo("ACT4");
		assertThat(narrative.scenarioProgressing()).isTrue();
	}

	// 마지막 구간을 다 쓰면 FINISHED다 — 프론트의 완료 축하 화면이 이 값에 걸린다. 가격용 커서는 마지막
	// 분으로 clamp되지만 공개 판정은 clamp하지 않은 분을 써야 그 구간의 사건이 닫히지 않는다.
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
			instrument, NOW, NOW.toLocalDate(), 1L, TutorialPriceGenerator.VERSION_2, NOW);
		return attempt;
	}
}
