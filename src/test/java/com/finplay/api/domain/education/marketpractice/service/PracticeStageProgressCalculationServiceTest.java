// 튜토리얼 5단계 중 주문 방법·프리셋 단계의 완료 판정 규칙을 검증한다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.service.PracticeExitPlanQueryService;
import com.finplay.api.domain.order.service.PracticeRunFillKindDto;
import com.finplay.api.domain.order.service.TradeService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeStageProgressCalculationServiceTest {

	private static final Long ATTEMPT_ID = 11L;
	private static final Long USER_ID = 7L;
	private static final long RUN = 1L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 10, 0);

	private final TradeService tradeService = mock(TradeService.class);
	private final PracticeExitPlanQueryService practiceExitPlanQueryService = mock(
		PracticeExitPlanQueryService.class);
	private final PracticeStageProgressCalculationService service = new PracticeStageProgressCalculationService(
		tradeService, practiceExitPlanQueryService);

	@BeforeEach
	void stubNoExitPlans() {
		when(practiceExitPlanQueryService.findTriggeredSellOrderStatuses(anyLong(), anyLong()))
			.thenReturn(Map.of());
		// 052 EXITFREE-011 — 기준 단계 판정이 예약 존재도 근거로 센다. 기본은 "예약 없음"으로 두고
		// 그것을 보는 테스트만 덮어쓴다.
		when(practiceExitPlanQueryService.existsRunReservation(anyLong(), anyLong())).thenReturn(false);
	}

	// 종목을 고르기 전에는 실행 자체가 없다 — 원장을 읽지 않고 전부 false다.
	@Test
	void anAttemptWithoutAnInstrumentReportsNothingCompleted() {
		PracticeStageProgressResponse progress = service.calculate(selectingAttempt());

		assertThat(progress).isEqualTo(PracticeStageProgressResponse.none());
	}

	@Test
	void buyingWithoutSellingDoesNotCompleteTheStage() {
		stubFills(fill(101L, OrderSide.BUY, OrderType.MARKET));

		PracticeStageProgressResponse progress = service.calculate(startedAttempt(null));

		assertThat(progress.marketBuySellCompleted()).isFalse();
		assertThat(progress.limitBuySellCompleted()).isFalse();
	}

	@Test
	void aMarketRoundTripCompletesOnlyTheMarketStage() {
		stubFills(
			fill(101L, OrderSide.BUY, OrderType.MARKET),
			fill(102L, OrderSide.SELL, OrderType.MARKET));

		PracticeStageProgressResponse progress = service.calculate(startedAttempt(null));

		assertThat(progress.marketBuySellCompleted()).isTrue();
		assertThat(progress.limitBuySellCompleted()).isFalse();
	}

	@Test
	void aLimitRoundTripCompletesOnlyTheLimitStage() {
		stubFills(
			fill(201L, OrderSide.BUY, OrderType.LIMIT),
			fill(202L, OrderSide.SELL, OrderType.LIMIT));

		PracticeStageProgressResponse progress = service.calculate(startedAttempt(null));

		assertThat(progress.limitBuySellCompleted()).isTrue();
		assertThat(progress.marketBuySellCompleted()).isFalse();
	}

	// 유형이 섞인 왕복은 어느 쪽도 완결이 아니다 — 시장가로 사서 지정가로 팔았다면 두 단계 모두 절반이다.
	@Test
	void aMixedRoundTripCompletesNeitherStage() {
		stubFills(
			fill(101L, OrderSide.BUY, OrderType.MARKET),
			fill(202L, OrderSide.SELL, OrderType.LIMIT));

		PracticeStageProgressResponse progress = service.calculate(startedAttempt(null));

		assertThat(progress.marketBuySellCompleted()).isFalse();
		assertThat(progress.limitBuySellCompleted()).isFalse();
	}

	/**
	 * <b>이 서비스에서 가장 중요한 한 건이다.</b> 손절·익절 예약이 발동시킨 매도는 원장에 {@code MARKET}
	 * 주문으로 남는다({@code ExitPlanFillService.executeMarketSell}). 빼지 않으면 프리셋에 청산당하기만 한
	 * 사용자가 "시장가로 팔아봤다"로 판정돼 다음 단계가 열린다.
	 */
	@Test
	void aSellTriggeredByTheExitPresetIsNotCountedAsAMarketSell() {
		stubFills(
			fill(101L, OrderSide.BUY, OrderType.MARKET),
			fill(102L, OrderSide.SELL, OrderType.MARKET));
		when(practiceExitPlanQueryService.findTriggeredSellOrderStatuses(ATTEMPT_ID, RUN))
			.thenReturn(Map.of(102L, ExitPlanStatus.FILLED_STOP_LOSS));

		PracticeStageProgressResponse progress = service.calculate(startedAttempt(null));

		assertThat(progress.marketBuySellCompleted()).isFalse();
	}

	// 예약에 한 번 청산당한 뒤 직접 시장가로 팔면 그제야 통과한다 — 발동분만 빠지고 나머지는 그대로 센다.
	@Test
	void aManualMarketSellAfterAnAutomaticOneStillCompletesTheStage() {
		stubFills(
			fill(101L, OrderSide.BUY, OrderType.MARKET),
			fill(102L, OrderSide.SELL, OrderType.MARKET),
			fill(103L, OrderSide.BUY, OrderType.MARKET),
			fill(104L, OrderSide.SELL, OrderType.MARKET));
		when(practiceExitPlanQueryService.findTriggeredSellOrderStatuses(ATTEMPT_ID, RUN))
			.thenReturn(Map.of(102L, ExitPlanStatus.FILLED_STOP_LOSS));

		PracticeStageProgressResponse progress = service.calculate(startedAttempt(null));

		assertThat(progress.marketBuySellCompleted()).isTrue();
	}

	// 아직 고르지 않았으면 미통과다. 이 사용자의 진입 snapshot에도 기본 프리셋 BALANCED가 박히지만
	// (042 EXITPRESET-002) 그것으로 통과시키지 않는다 — 고른 적이 없기 때문이다.
	@Test
	void notChoosingAPresetLeavesTheStageIncomplete() {
		stubFills(fill(101L, OrderSide.BUY, OrderType.MARKET));

		PracticeStageProgressResponse progress = service.calculate(startedAttempt(null));

		assertThat(progress.exitPresetSelected()).isFalse();
	}

	@Test
	void choosingAPresetCompletesTheStage() {
		stubFills();

		PracticeStageProgressResponse progress = service.calculate(startedAttempt(ExitPreset.CAUTIOUS));

		assertThat(progress.exitPresetSelected()).isTrue();
	}

	/**
	 * <b>단조성.</b> 이미 통과한 사용자가 다음 진입을 준비하며 프리셋을 바꾸는 것은 042 EXITPRESET-003이
	 * 허용하는 정상 조작이다. "고른 프리셋으로 진입까지 했는가"로 판정하면 바꾼 프리셋의 진입이 아직
	 * 없어 <b>통과가 취소되고 화면이 이미 연 단계를 되잠근다.</b>
	 */
	@Test
	void changingThePresetAfterPassingKeepsTheStageComplete() {
		stubFills(
			fill(101L, OrderSide.BUY, OrderType.MARKET),
			fill(102L, OrderSide.SELL, OrderType.MARKET));
		PracticeAttempt attempt = startedAttempt(ExitPreset.CAUTIOUS);
		assertThat(service.calculate(attempt).exitPresetSelected()).isTrue();

		attempt.selectExitPreset(ExitPreset.RELAXED, NOW);

		assertThat(service.calculate(attempt).exitPresetSelected()).isTrue();
	}

	/**
	 * 052 EXITFREE-020 — <b>예약을 건 것도 "기준을 정했다"로 센다.</b> 3단계 화면이 매수 폼의 비율 입력을
	 * 없애고 예약 요청 본문으로 비율을 보내게 되면서, {@code PUT .../exit-rates}를 한 번도 부르지 않는
	 * 실행이 정상 경로가 됐다. attempt 컬럼만 보면 이 사용자는 <b>영영 미완</b>으로 남는다.
	 */
	@Test
	void reservingAnExitPlanCompletesTheStageEvenWithoutCallingTheRatesApi() {
		stubFills(fill(101L, OrderSide.BUY, OrderType.MARKET));
		when(practiceExitPlanQueryService.existsRunReservation(ATTEMPT_ID, RUN)).thenReturn(true);

		PracticeStageProgressResponse progress = service.calculate(startedAttempt(null));

		assertThat(progress.exitPresetSelected()).isTrue();
	}

	/**
	 * <b>단조성 — 취소해도 되돌아가지 않는다.</b> 근거는 "예약 행이 있는가"이고 예약은 취소돼도 상태만
	 * 바뀌지 사라지지 않는다. 그래서 판정이 <b>상태를 묻지 않는 조회</b>를 쓰는지가 핵심이다 — PENDING만
	 * 세는 구현이면 사용자가 예약을 취소하는 순간 이미 열린 단계가 눈앞에서 되잠긴다.
	 *
	 * <p>여기서 고정할 수 있는 것은 "판정이 상태 무관 조회를 쓴다"까지다. 그 조회가 실제로 취소된 행까지
	 * 세는지는 리포지터리 메서드 이름({@code existsByPracticeAttemptIdAndPracticeAttemptRunNumber} —
	 * 상태 조건이 없다)과 통합 경로가 보증한다.
	 */
	@Test
	void theStageJudgmentAsksAStatusAgnosticQuerySoCancellingCannotReopenIt() {
		stubFills(fill(101L, OrderSide.BUY, OrderType.MARKET));
		when(practiceExitPlanQueryService.existsRunReservation(ATTEMPT_ID, RUN)).thenReturn(true);

		assertThat(service.calculate(startedAttempt(null)).exitPresetSelected()).isTrue();

		verify(practiceExitPlanQueryService).existsRunReservation(ATTEMPT_ID, RUN);
		// 상태로 거르는 조회로 판정하지 않는다.
		verify(practiceExitPlanQueryService, never()).summarizeCurrentRun(anyLong(), anyLong());
	}

	/**
	 * <b>대본을 쓰지 않는 실행의 예약은 근거가 아니다.</b> 그쪽은 042 그대로 매수 체결이 서버가 자동으로
	 * 거는 예약이라 사용자가 아무것도 고르지 않아도 행이 생긴다 — 그것으로 통과시키면 TUTORIAL-STAGE-001이
	 * 배제한 "고른 값으로 진입까지 했는가"류의 오판이 되살아난다.
	 */
	@Test
	void anAutomaticReservationInALegacyRunIsNotEvidenceOfChoosing() {
		stubFills(fill(101L, OrderSide.BUY, OrderType.MARKET));
		when(practiceExitPlanQueryService.existsRunReservation(ATTEMPT_ID, RUN)).thenReturn(true);

		PracticeStageProgressResponse progress = service.calculate(legacyAttempt());

		assertThat(progress.exitPresetSelected()).isFalse();
	}

	private void stubFills(PracticeRunFillKindDto... fills) {
		when(tradeService.findPracticeRunFillKinds(ATTEMPT_ID, RUN)).thenReturn(List.of(fills));
	}

	private static PracticeRunFillKindDto fill(Long orderId, OrderSide side, OrderType orderType) {
		return new PracticeRunFillKindDto(orderId, side, orderType);
	}

	private static PracticeAttempt selectingAttempt() {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		return attempt;
	}

	// 대본을 쓰지 않는 실행(생성기 버전 1) — scenarioScriptId()가 null이라 자동 예약 경로에 남아 있다.
	private static PracticeAttempt legacyAttempt() {
		PracticeAttempt attempt = selectingAttempt();
		attempt.selectInstrument(instrument(), NOW, LocalDate.from(NOW), 1L, (short)1, null, NOW);
		return attempt;
	}

	private static Instrument instrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "SAMPLE", "튜토리얼 샘플", BigDecimal.ONE, 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 21L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrument;
	}

	private static PracticeAttempt startedAttempt(ExitPreset preset) {
		PracticeAttempt attempt = selectingAttempt();
		attempt.selectInstrument(instrument(), NOW, LocalDate.from(NOW), 1L, (short)2, null, NOW);
		if (preset != null) {
			attempt.selectExitPreset(preset, NOW);
		}
		return attempt;
	}
}
