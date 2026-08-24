// PracticeAttempt의 재시작 전이(완료 attempt 포함)와 대본 위치 초기화를 검증한다.
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

	// 엔티티는 의존 방향 때문에 market.service의 상수를 import하지 않고 같은 값을 자기 안에 선언한다
	// (PR #474 리뷰). 두 값이 조용히 갈라지면 대본 실행이 버전 1로 취급돼 5분 마감이 되살아나므로,
	// 그 동등성을 여기서 고정한다 — 테스트는 두 도메인을 모두 볼 수 있다.
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

	// 049 배포 순간 진행 중이던 실행의 상태다 — 컬럼은 NULL인데 커서에는 041 구간 id가 살아 있다.
	// 이 해석이 빠지면 그 사용자는 "대본이 저작되지 않은 식별자입니다"로 500에 갇히고 회복 수단이
	// 재시작뿐이다.
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

	// 버전 1 실행에 식별자가 남아 있어도 대본을 쓰지 않는다. 여기서 새면 5분 마감이 걸린 실행이 대본
	// 가격을 받게 된다.
	@Test
	void scenarioScriptIdIsNullForNonScriptRunEvenWhenTheColumnHoldsAValue() {
		PracticeAttempt attempt = PracticeAttempt.create(1L, Market.CRYPTO, NOW.minusHours(1));
		selectInstrument(attempt, TutorialPriceGenerator.VERSION_1, TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);

		assertThat(attempt.scenarioScriptId()).isNull();
	}

	// **파생 접근자로 단언하면 이 회귀를 못 잡는다** — restart가 generatorVersion도 지우므로 컬럼이
	// 남아 있어도 scenarioScriptId()는 null을 돌려준다. 원본 필드를 직접 읽는다.
	@Test
	void restartClearsThePersistedScriptIdColumnItselfNotJustTheDerivedView() {
		PracticeAttempt attempt = PracticeAttempt.create(1L, Market.CRYPTO, NOW.minusHours(1));
		selectInstrument(attempt, TutorialPriceGenerator.VERSION_2, TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);

		attempt.restart(NOW);

		assertThat(ReflectionTestUtils.getField(attempt, "scenarioScriptId")).isNull();
	}

	// clearScenarioProgress가 selectInstrument 안에서 먼저 돌기 때문에, 순서가 뒤집히면 새로 박은
	// 식별자가 곧바로 지워진다. 이전 실행의 다른 식별자가 남은 상태에서 확인한다.
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
