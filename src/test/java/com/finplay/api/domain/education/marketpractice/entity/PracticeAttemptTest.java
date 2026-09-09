package com.finplay.api.domain.education.marketpractice.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeAttemptTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 17, 10, 0, 0);

	@Test
	void scenarioGeneratorVersionMatchesTheMarketGeneratorConstant() {
		assertThat(usesScenarioScriptFor(TutorialPriceGenerator.VERSION_2)).isTrue();
		assertThat(usesScenarioScriptFor(TutorialPriceGenerator.VERSION_1)).isFalse();
	}

	@Test
	void attemptWithoutGeneratorVersionDoesNotUseTheScript() {
		assertThat(PracticeAttempt.create(1L, Market.CRYPTO, NOW).usesScenarioScript()).isFalse();
	}

	private static boolean usesScenarioScriptFor(short generatorVersion) {
		PracticeAttempt attempt = PracticeAttempt.create(1L, Market.CRYPTO, NOW);
		ReflectionTestUtils.setField(attempt, "generatorVersion", generatorVersion);
		return attempt.usesScenarioScript();
	}

	@Test
	void restartFromCompletedIncrementsRunAndResetsToSelectingInstrument() {
		PracticeAttempt attempt = selectedAttempt();
		ReflectionTestUtils.setField(attempt, "status", PracticeAttemptStatus.COMPLETED);
		ReflectionTestUtils.setField(attempt, "completedAt", NOW.minusDays(1));

		attempt.restart(NOW);

		assertThat(attempt.getStatus()).isEqualTo(PracticeAttemptStatus.SELECTING_INSTRUMENT);
		assertThat(attempt.getRunNumber()).isEqualTo(2L);
		assertThat(attempt.getCompletedAt()).isNull();
		assertThat(attempt.getInstrument()).isNull();
		assertThat(attempt.getAnchorAt()).isNull();
		assertThat(attempt.getTutorialDate()).isNull();
		assertThat(attempt.getPriceSeed()).isNull();
		assertThat(attempt.getGeneratorVersion()).isNull();
		assertThat(attempt.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	void restartFromInProgressIncrementsRunAndResetsSelectionState() {
		PracticeAttempt attempt = selectedAttempt();

		attempt.restart(NOW);

		assertThat(attempt.getStatus()).isEqualTo(PracticeAttemptStatus.SELECTING_INSTRUMENT);
		assertThat(attempt.getRunNumber()).isEqualTo(2L);
		assertThat(attempt.getInstrument()).isNull();
	}

	@Test
	void restartClearsScenarioProgressAndInProgressCandle() {
		PracticeAttempt attempt = selectedAttempt();
		putScenarioProgress(attempt);

		attempt.restart(NOW);

		assertScenarioProgressCleared(attempt);
	}

	@Test
	void selectInstrumentClearsScenarioProgressLeftFromPreviousRun() {
		PracticeAttempt attempt = PracticeAttempt.create(1L, Market.CRYPTO, NOW.minusHours(1));
		putScenarioProgress(attempt);

		selectInstrument(attempt);

		assertScenarioProgressCleared(attempt);
	}

	@Test
	void restartClearsSelectedExitPreset() {
		PracticeAttempt attempt = selectedAttempt();
		ReflectionTestUtils.setField(attempt, "exitPreset", ExitPreset.RELAXED);

		attempt.restart(NOW);

		assertThat(attempt.getExitPreset()).isNull();
	}

	@Test
	void scenarioScriptIdFallsBackToTheStoryScriptWhenTheColumnIsNullOnAScriptRun() {
		PracticeAttempt attempt = PracticeAttempt.create(1L, Market.CRYPTO, NOW.minusHours(1));
		selectInstrument(attempt, TutorialPriceGenerator.VERSION_2, null);
		ReflectionTestUtils.setField(attempt, "scenarioStageId", "ACT2_RUMOR");

		assertThat(attempt.scenarioScriptId()).isEqualTo(TutorialScenarioScriptId.CRYPTO_STORY_V1);
	}

	@Test
	void scenarioScriptIdReturnsThePersistedIdentifierWhenPresent() {
		PracticeAttempt attempt = PracticeAttempt.create(1L, Market.CRYPTO, NOW.minusHours(1));
		selectInstrument(attempt, TutorialPriceGenerator.VERSION_2, TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);

		assertThat(attempt.scenarioScriptId()).isEqualTo(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
	}

	@Test
	void scenarioScriptIdIsNullForNonScriptRunEvenWhenTheColumnHoldsAValue() {
		PracticeAttempt attempt = PracticeAttempt.create(1L, Market.CRYPTO, NOW.minusHours(1));
		selectInstrument(attempt, TutorialPriceGenerator.VERSION_1, TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);

		assertThat(attempt.scenarioScriptId()).isNull();
	}

	@Test
	void restartClearsThePersistedScriptIdColumnItselfNotJustTheDerivedView() {
		PracticeAttempt attempt = PracticeAttempt.create(1L, Market.CRYPTO, NOW.minusHours(1));
		selectInstrument(attempt, TutorialPriceGenerator.VERSION_2, TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);

		attempt.restart(NOW);

		assertThat(ReflectionTestUtils.getField(attempt, "scenarioScriptId")).isNull();
	}

	@Test
	void selectInstrumentReplacesTheScriptIdLeftFromThePreviousRun() {
		PracticeAttempt attempt = PracticeAttempt.create(1L, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(
			attempt, "scenarioScriptId", TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);

		selectInstrument(attempt, TutorialPriceGenerator.VERSION_2, TutorialScenarioScriptId.CRYPTO_STORY_V1);

		assertThat(ReflectionTestUtils.getField(attempt, "scenarioScriptId"))
			.isEqualTo(TutorialScenarioScriptId.CRYPTO_STORY_V1);
	}

	private static void putScenarioProgress(PracticeAttempt attempt) {
		ReflectionTestUtils.setField(attempt, "scenarioStageId", "ACT4_CRASH");
		ReflectionTestUtils.setField(attempt, "scenarioStageElapsedSeconds", 57L);
		ReflectionTestUtils.setField(attempt, "scenarioCandleOpen", new BigDecimal("10000.00000000"));
		ReflectionTestUtils.setField(attempt, "scenarioCandleHigh", new BigDecimal("10180.00000000"));
		ReflectionTestUtils.setField(attempt, "scenarioCandleLow", new BigDecimal("7900.00000000"));
	}

	private static void assertScenarioProgressCleared(PracticeAttempt attempt) {
		assertThat(attempt.getScenarioStageId()).isNull();
		assertThat(attempt.getScenarioStageElapsedSeconds()).isNull();
		assertThat(attempt.getScenarioCandleOpen()).isNull();
		assertThat(attempt.getScenarioCandleHigh()).isNull();
		assertThat(attempt.getScenarioCandleLow()).isNull();
	}

	private static PracticeAttempt selectedAttempt() {
		PracticeAttempt attempt = PracticeAttempt.create(1L, Market.CRYPTO, NOW.minusHours(1));
		selectInstrument(attempt);
		return attempt;
	}

	private static void selectInstrument(PracticeAttempt attempt) {
		selectInstrument(attempt, TutorialPriceGenerator.VERSION_1, null);
	}

	private static void selectInstrument(
		PracticeAttempt attempt, short generatorVersion, TutorialScenarioScriptId scriptId) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 21L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 123L, generatorVersion,
			scriptId, NOW.minusMinutes(10));
	}
}
