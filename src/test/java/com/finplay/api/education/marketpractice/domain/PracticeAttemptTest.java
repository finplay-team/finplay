// PracticeAttempt의 재시작 전이(완료 attempt 포함)와 대본 위치 초기화를 검증한다.
package com.finplay.api.education.marketpractice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeAttemptTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 17, 10, 0, 0);

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

	// 재시작이 대본 위치를 지우지 않으면 재시작한 사용자의 첫 화면에 이전 실행의 4막 저점이 그대로 남는다
	// (041 plan §재시작 시 초기화).
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

	// 프리셋 선택은 실행 세대에 귀속된다(042 EXITPRESET-009). 재시작이 지우지 않으면 이전 실행에서 고른
	// 기준이 새 실행에 조용히 따라붙어, 사용자가 고르지 않은 기준으로 손절선이 잡힌다.
	@Test
	void restartClearsSelectedExitPreset() {
		PracticeAttempt attempt = selectedAttempt();
		ReflectionTestUtils.setField(attempt, "exitPreset", ExitPreset.RELAXED);

		attempt.restart(NOW);

		assertThat(attempt.getExitPreset()).isNull();
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
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 21L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)1,
			NOW.minusMinutes(10));
	}
}
