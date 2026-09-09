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
		when(practiceExitPlanQueryService.existsRunReservation(anyLong(), anyLong())).thenReturn(false);
	}

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

	@Test
	void aMixedRoundTripCompletesNeitherStage() {
		stubFills(
			fill(101L, OrderSide.BUY, OrderType.MARKET),
			fill(202L, OrderSide.SELL, OrderType.LIMIT));

		PracticeStageProgressResponse progress = service.calculate(startedAttempt(null));

		assertThat(progress.marketBuySellCompleted()).isFalse();
		assertThat(progress.limitBuySellCompleted()).isFalse();
	}

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

	@Test
	void reservingAnExitPlanCompletesTheStageEvenWithoutCallingTheRatesApi() {
		stubFills(fill(101L, OrderSide.BUY, OrderType.MARKET));
		when(practiceExitPlanQueryService.existsRunReservation(ATTEMPT_ID, RUN)).thenReturn(true);

		PracticeStageProgressResponse progress = service.calculate(startedAttempt(null));

		assertThat(progress.exitPresetSelected()).isTrue();
	}

	@Test
	void theStageJudgmentAsksAStatusAgnosticQuerySoCancellingCannotReopenIt() {
		stubFills(fill(101L, OrderSide.BUY, OrderType.MARKET));
		when(practiceExitPlanQueryService.existsRunReservation(ATTEMPT_ID, RUN)).thenReturn(true);

		assertThat(service.calculate(startedAttempt(null)).exitPresetSelected()).isTrue();

		verify(practiceExitPlanQueryService).existsRunReservation(ATTEMPT_ID, RUN);
		verify(practiceExitPlanQueryService, never()).summarizeCurrentRun(anyLong(), anyLong());
	}

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
