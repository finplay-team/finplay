// 튜토리얼 5단계 중 주문 방법·프리셋 단계의 완료 판정 규칙을 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.education.marketpractice.domain.ExitPreset;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.domain.ExitPlanStatus;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.service.PracticeExitPlanQueryService;
import com.finplay.api.order.service.PracticeRunFillKindDto;
import com.finplay.api.order.service.TradeService;
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

	private static PracticeAttempt startedAttempt(ExitPreset preset) {
		PracticeAttempt attempt = selectingAttempt();
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "SAMPLE", "튜토리얼 샘플", BigDecimal.ONE, 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 21L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(instrument, NOW, LocalDate.from(NOW), 1L, (short)2, null, NOW);
		if (preset != null) {
			attempt.selectExitPreset(preset, NOW);
		}
		return attempt;
	}
}
