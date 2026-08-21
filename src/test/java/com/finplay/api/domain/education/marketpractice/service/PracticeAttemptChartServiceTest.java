// 튜토리얼 차트 GET의 무부수효과와 명시적 tick의 지정가 정산·완료 잠금을 검증한다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.order.service.PracticeOrderSettlementService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeAttemptChartServiceTest {

	private static final Long USER_ID = 7L;
	private static final Instant NOW_INSTANT = Instant.parse("2026-08-14T03:00:05Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
	private final PracticeAttemptRepository attemptRepository = mock(PracticeAttemptRepository.class);
	private final PracticeOrderSettlementService settlementService = mock(PracticeOrderSettlementService.class);
	private final PracticeAttemptCanonicalPriceService canonicalPriceService = new PracticeAttemptCanonicalPriceService(
		attemptRepository, new TutorialPriceGenerator(),
		new com.finplay.api.domain.market.service.TutorialScenarioScriptLoader(
			new tools.jackson.databind.ObjectMapper()));
	private final PracticeScenarioProgressService progressService = mock(PracticeScenarioProgressService.class);
	private final PracticeAttemptChartService service = new PracticeAttemptChartService(
		attemptRepository, canonicalPriceService, progressService, settlementService,
		Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));

	@Test
	void getChartReturnsExactCanonicalCurrentCloseWithoutSettlingOrders() {
		PracticeAttempt attempt = selectedAttempt();
		when(attemptRepository.findByUserIdAndMarket(USER_ID, Market.CRYPTO)).thenReturn(Optional.of(attempt));

		PracticeTutorialChartResponse response = service.getChart(USER_ID, Market.CRYPTO);

		assertThat(response.candles()).hasSize(30);
		assertThat(response.candles().get(29).close())
			.isEqualByComparingTo(canonicalPriceService.canonicalPrice(attempt, NOW));
		assertThat(response.virtualDateTime()).isEqualTo(attempt.getTutorialDate().atTime(12, 1));
		assertThat(response.secondsPerVirtualMinute()).isEqualTo(3);
		verifyNoInteractions(settlementService);
	}

	@Test
	void tickLocksAttemptAndSettlesCurrentRunBeforeReturningCanonicalChart() {
		PracticeAttempt attempt = selectedAttempt();
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));

		PracticeTutorialChartResponse response = service.tick(USER_ID, Market.CRYPTO);

		verify(settlementService).settleCurrentRun(
			attempt.getId(), attempt.getRunNumber(), NOW,
			canonicalPriceService.canonicalPrice(attempt, NOW));
		assertThat(response.candles().get(29).close())
			.isEqualByComparingTo(canonicalPriceService.canonicalPrice(attempt, NOW));
	}

	@Test
	void getChartRejectsMissingAttemptAsLocked() {
		when(attemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getChart(USER_ID, Market.STOCK))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_STEP_LOCKED));
	}

	@Test
	void tickRejectsCompletedAttemptWithoutSettlement() {
		PracticeAttempt attempt = selectedAttempt();
		ReflectionTestUtils.setField(attempt, "status", PracticeAttemptStatus.COMPLETED);
		ReflectionTestUtils.setField(attempt, "completedAt", NOW.minusDays(1));
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));

		assertThatThrownBy(() -> service.tick(USER_ID, Market.CRYPTO))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_ALREADY_COMPLETED));

		verify(settlementService, never()).settleCurrentRun(
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	// 041 5번 — 생성기 버전 2는 진행 계산이 커서를 밀면서 분마다 정산한다. 여기서 settleCurrentRun을 한 번
	// 더 부르면 같은 tick의 마지막 분이 두 번 판정된다.
	@Test
	void tickDelegatesToScenarioProgressForVersionTwoAttemptsInsteadOfSettlingOnce() {
		PracticeAttempt attempt = scenarioAttempt();
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));

		service.tick(USER_ID, Market.CRYPTO);

		verify(progressService).advance(attempt, NOW);
		verifyNoInteractions(settlementService);
	}

	// GET chart는 순수 조회다 — 진행 계산을 부르지 않으므로 tick 없이 새로고침해도 대본이 진행하지 않는다.
	@Test
	void getChartDoesNotAdvanceTheScenarioCursor() {
		PracticeAttempt attempt = scenarioAttempt();
		when(attemptRepository.findByUserIdAndMarket(USER_ID, Market.CRYPTO)).thenReturn(Optional.of(attempt));

		PracticeTutorialChartResponse response = service.getChart(USER_ID, Market.CRYPTO);

		assertThat(response.candles()).hasSize(30);
		assertThat(response.candles().get(29).close())
			.isEqualByComparingTo(canonicalPriceService.canonicalPrice(attempt, NOW));
		assertThat(response.candles().get(29).open()).isEqualByComparingTo(attempt.getScenarioCandleOpen());
		verifyNoInteractions(progressService, settlementService);
	}

	// 진행 중 봉의 고가·저가는 attempt에 누적된 값을 그대로 쓴다 — 대본 위치가 단조가 아니라 지나온 경로를
	// 되접어 만들 수 없다(041 plan §데이터 모델).
	@Test
	void scenarioChartUsesPersistedCandleExtremes() {
		PracticeAttempt attempt = scenarioAttempt();
		attempt.extendScenarioCandle(new BigDecimal("12000.00000000"));
		attempt.extendScenarioCandle(new BigDecimal("8000.00000000"));
		when(attemptRepository.findByUserIdAndMarket(USER_ID, Market.CRYPTO)).thenReturn(Optional.of(attempt));

		PracticeTutorialChartResponse response = service.getChart(USER_ID, Market.CRYPTO);

		assertThat(response.candles().get(29).high()).isEqualByComparingTo(new BigDecimal("12000.00000000"));
		assertThat(response.candles().get(29).low()).isEqualByComparingTo(new BigDecimal("8000.00000000"));
	}

	// 049 tasks 3번 검증 — 2단계 대본(사건 없음)은 안내 범위가 나가야 한다.
	@Test
	void getChartExposesPriceGuideRangeForOrderBasicsScenarioScript() {
		PracticeAttempt attempt = orderBasicsAttempt();
		when(attemptRepository.findByUserIdAndMarket(USER_ID, Market.CRYPTO)).thenReturn(Optional.of(attempt));

		PracticeTutorialChartResponse response = service.getChart(USER_ID, Market.CRYPTO);

		assertThat(response.priceGuideRange()).isNotNull();
		assertThat(response.priceGuideRange().low()).isEqualByComparingTo(new BigDecimal("90000.00000000"));
		assertThat(response.priceGuideRange().high()).isEqualByComparingTo(new BigDecimal("110000.00000000"));
	}

	// 049 tasks 3번 검증 — 041 대본(사건 있음)은 안내 범위가 null이어야 한다. 이 단정이 SCENARIO-015·020을
	// 지킨다 — 안내 범위가 나가면 4막 폭락의 저점(7900대)이 사건 공개 전에 노출된다.
	@Test
	void getChartLeavesPriceGuideRangeNullForStoryScenarioScriptWithEvents() {
		PracticeAttempt attempt = scenarioAttempt();
		when(attemptRepository.findByUserIdAndMarket(USER_ID, Market.CRYPTO)).thenReturn(Optional.of(attempt));

		PracticeTutorialChartResponse response = service.getChart(USER_ID, Market.CRYPTO);

		assertThat(response.priceGuideRange()).isNull();
	}

	// scenarioScriptId를 null로 두면 파생 접근자가 CRYPTO_STORY_V1(041 대본, events 있음)로 해석한다
	// (PracticeAttempt.scenarioScriptId() 하위 호환 규칙, 049 tasks 2번).
	private static PracticeAttempt scenarioAttempt() {
		PracticeAttempt attempt = attempt(TutorialPriceGenerator.VERSION_2, null);
		attempt.startScenarioProgress("ACT1_RISE", new BigDecimal("10000.00000000"), NOW.minusSeconds(3));
		attempt.moveScenarioCursor("ACT1_RISE", 12L);
		return attempt;
	}

	// 049 tasks 3번 — 2단계 대본(CRYPTO_ORDER_BASICS_V1)은 events가 비어 있어 안내 범위가 나가야 한다.
	private static PracticeAttempt orderBasicsAttempt() {
		PracticeAttempt attempt = attempt(TutorialPriceGenerator.VERSION_2,
			TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		attempt.startScenarioProgress("ORDER_BASICS", new BigDecimal("100000.00000000"), NOW.minusSeconds(3));
		attempt.moveScenarioCursor("ORDER_BASICS", 12L);
		return attempt;
	}

	private static PracticeAttempt selectedAttempt() {
		return attempt(TutorialPriceGenerator.VERSION_1, null);
	}

	private static PracticeAttempt attempt(short generatorVersion, TutorialScenarioScriptId scenarioScriptId) {
		LocalDateTime anchor = NOW.minusSeconds(5);
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", 11L);
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 21L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(
			instrument, anchor, NOW.toLocalDate(), 123_456_789L, generatorVersion, scenarioScriptId, anchor);
		return attempt;
	}
}
