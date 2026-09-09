package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.market.service.TutorialScenarioScriptLoader;
import com.finplay.api.domain.order.service.PracticeOrderSettlementService;
import com.finplay.api.domain.order.service.TradeService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class PracticeScenarioProgressServiceTest {

	private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 8, 19, 12, 0);
	private static final Long USER_ID = 7L;
	private static final Long ATTEMPT_ID = 11L;
	private static final Long INSTRUMENT_ID = 21L;
	private static final BigDecimal CRYPTO_BASE_PRICE = new BigDecimal("10000.00000000");

	private final PracticeOrderSettlementService settlementService = mock(PracticeOrderSettlementService.class);
	private final TradeService tradeService = mock(TradeService.class);
	private final PracticeAttemptCanonicalPriceService canonicalPriceService = new PracticeAttemptCanonicalPriceService(
		mock(PracticeAttemptRepository.class),
		new TutorialPriceGenerator(),
		new TutorialScenarioScriptLoader(new ObjectMapper()));
	private final PracticeExitPlanReservationService exitPlanReservationService = mock(
		PracticeExitPlanReservationService.class);
	private final PracticeScenarioProgressService service = new PracticeScenarioProgressService(
		canonicalPriceService, settlementService, tradeService, exitPlanReservationService);

	@BeforeEach
	void setUp() {
		holdNothing();
		when(tradeService.findLatestPracticeRunBuyExecutedAt(anyLong(), anyLong())).thenReturn(Optional.empty());
		when(exitPlanReservationService.entryReservationSatisfied(any(PracticeAttempt.class))).thenReturn(true);
	}

	@Test
	void firstTickInitializesCursorToFirstStageAndOpensCandle() {
		PracticeAttempt attempt = scenarioAttempt();

		service.advance(attempt, ANCHOR);

		assertThat(attempt.getScenarioStageId()).isEqualTo("IDLE_ENTRY");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
		assertThat(attempt.getScenarioCandleOpen()).isEqualByComparingTo(CRYPTO_BASE_PRICE);
		assertThat(attempt.getScenarioCandleHigh()).isEqualByComparingTo(CRYPTO_BASE_PRICE);
		assertThat(attempt.getScenarioCandleLow()).isEqualByComparingTo(CRYPTO_BASE_PRICE);
		assertThat(attempt.getScenarioProgressUpdatedAt()).isEqualTo(ANCHOR);
	}

	@Test
	void firstTickLeavesTheIdleLoopImmediatelyWhenTheUserAlreadyBought() {
		PracticeAttempt attempt = scenarioAttempt();
		holdQuantity("0.5");

		service.advance(attempt, ANCHOR);

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
		assertThat(attempt.getScenarioProgressUpdatedAt()).isEqualTo(ANCHOR);
	}

	@Test
	void firstTickStaysInTheIdleLoopWhenNothingIsHeld() {
		PracticeAttempt attempt = scenarioAttempt();

		service.advance(attempt, ANCHOR);

		assertThat(attempt.getScenarioStageId()).isEqualTo("IDLE_ENTRY");
	}

	@Test
	void tickThatFillsAtTheLoopRewindPointStillLeavesTheIdleLoopInTheSameTick() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 57L, ANCHOR);
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L))
			.thenReturn(BigDecimal.ZERO, new BigDecimal("0.5"));

		service.advance(attempt, ANCHOR.plusSeconds(3));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
	}

	@Test
	void idleLoopAdvancesWithoutHoldingAndRewindsAtTheEnd() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 57L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(3));

		assertThat(attempt.getScenarioStageId()).isEqualTo("IDLE_ENTRY");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
	}

	@Test
	void buyingInsideIdleLoopJumpsToTheZeroMinuteOfTheNextProgressStage() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 15L, ANCHOR);
		holdQuantity("3");
		when(tradeService.findLatestPracticeRunBuyExecutedAt(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(ANCHOR.plusSeconds(3)));

		service.advance(attempt, ANCHOR.plusSeconds(3));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
	}

	@Test
	void buyingInsideReentryIdleLoopJumpsToTheThirdAct() {
		PracticeAttempt attempt = startedAt("IDLE_REENTRY", 9L, ANCHOR);
		holdQuantity("2");
		when(tradeService.findLatestPracticeRunBuyExecutedAt(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(ANCHOR.plusSeconds(3)));

		service.advance(attempt, ANCHOR.plusSeconds(3));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT3_REBOUND");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
	}

	@Test
	void idleLoopHoldsTheStoryUntilTheEntryHasAReservation() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 15L, ANCHOR);
		holdQuantity("3");
		when(exitPlanReservationService.entryReservationSatisfied(any(PracticeAttempt.class))).thenReturn(false);

		service.advance(attempt, ANCHOR.plusSeconds(3));

		assertThat(attempt.getScenarioStageId()).isEqualTo("IDLE_ENTRY");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(18L);
		verify(settlementService, times(1))
			.settleCurrentRun(eq(ATTEMPT_ID), eq(1L), any(LocalDateTime.class), any(BigDecimal.class));
	}

	@Test
	void theStoryStartsOnTheFirstTickAfterTheReservationIsCreated() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 15L, ANCHOR);
		holdQuantity("3");
		when(exitPlanReservationService.entryReservationSatisfied(any(PracticeAttempt.class))).thenReturn(false);
		when(tradeService.findLatestPracticeRunBuyExecutedAt(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(ANCHOR.plusSeconds(1)));

		service.advance(attempt, ANCHOR.plusSeconds(3));
		assertThat(attempt.getScenarioStageId()).isEqualTo("IDLE_ENTRY");

		when(exitPlanReservationService.entryReservationSatisfied(any(PracticeAttempt.class))).thenReturn(true);
		service.advance(attempt, ANCHOR.plusSeconds(6));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(3L);
	}

	@Test
	void reentryIdleLoopAlsoWaitsForTheReservation() {
		PracticeAttempt attempt = startedAt("IDLE_REENTRY", 9L, ANCHOR);
		holdQuantity("2");
		when(exitPlanReservationService.entryReservationSatisfied(any(PracticeAttempt.class))).thenReturn(false);

		service.advance(attempt, ANCHOR.plusSeconds(3));

		assertThat(attempt.getScenarioStageId()).isEqualTo("IDLE_REENTRY");
	}

	@Test
	void firstTickStaysInTheIdleLoopWhenTheEntryHasNoReservation() {
		PracticeAttempt attempt = scenarioAttempt();
		holdQuantity("0.5");
		when(exitPlanReservationService.entryReservationSatisfied(any(PracticeAttempt.class))).thenReturn(false);

		service.advance(attempt, ANCHOR);

		assertThat(attempt.getScenarioStageId()).isEqualTo("IDLE_ENTRY");
	}

	@Test
	void sellingWithoutAReservationLeavesTheUserInTheIdleLoopAndRebuyingResumesTheStory() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 15L, ANCHOR);
		holdNothing();
		when(exitPlanReservationService.entryReservationSatisfied(any(PracticeAttempt.class))).thenReturn(false);

		service.advance(attempt, ANCHOR.plusSeconds(3));
		assertThat(attempt.getScenarioStageId()).isEqualTo("IDLE_ENTRY");
		verify(exitPlanReservationService, never()).entryReservationSatisfied(any(PracticeAttempt.class));

		holdQuantity("1");
		when(exitPlanReservationService.entryReservationSatisfied(any(PracticeAttempt.class))).thenReturn(true);
		when(tradeService.findLatestPracticeRunBuyExecutedAt(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(ANCHOR.plusSeconds(6)));

		service.advance(attempt, ANCHOR.plusSeconds(6));
		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
	}

	@Test
	void escapingIdleLoopTruncatesDeltaToTheFillInstant() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 0L, ANCHOR);
		holdQuantity("1");
		when(tradeService.findLatestPracticeRunBuyExecutedAt(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(ANCHOR.plusSeconds(27)));

		service.advance(attempt, ANCHOR.plusSeconds(30));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(3L);
	}

	@Test
	void buyFilledMidTraversalLeavesTheIdleLoopWithinTheSameTick() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 0L, ANCHOR);
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L))
			.thenReturn(BigDecimal.ZERO, new BigDecimal("1"));
		when(tradeService.findLatestPracticeRunBuyExecutedAt(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(ANCHOR.plusSeconds(3)));

		service.advance(attempt, ANCHOR.plusSeconds(3));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isZero();
		verify(settlementService, times(2)).settleCurrentRun(eq(ATTEMPT_ID), eq(1L), any(LocalDateTime.class),
			any(BigDecimal.class));
	}

	@Test
	void secondsLeftAfterTheMidTraversalFillAreSpentInTheNextProgressStage() {
		PracticeAttempt attempt = startedAt("IDLE_ENTRY", 0L, ANCHOR);
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L))
			.thenReturn(BigDecimal.ZERO, new BigDecimal("1"));
		when(tradeService.findLatestPracticeRunBuyExecutedAt(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(ANCHOR.plusSeconds(3)));

		service.advance(attempt, ANCHOR.plusSeconds(9));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(6L);
		ArgumentCaptor<LocalDateTime> pricedAt = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(settlementService, times(4)).settleCurrentRun(eq(ATTEMPT_ID), eq(1L), pricedAt.capture(),
			any(BigDecimal.class));
		assertThat(pricedAt.getAllValues()).containsExactly(
			ANCHOR.plusSeconds(3), ANCHOR.plusSeconds(3), ANCHOR.plusSeconds(6), ANCHOR.plusSeconds(9));
	}

	@Test
	void progressStageAdvancesWithoutHolding() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 0L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(9));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT1_RISE");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(9L);
	}

	@Test
	void sellingDoesNotMoveTheCursorToTheReentryIdleStage() {
		PracticeAttempt attempt = startedAt("ACT2_CONFIRM", 0L, ANCHOR);
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L))
			.thenReturn(new BigDecimal("2"), new BigDecimal("2"), BigDecimal.ZERO);

		service.advance(attempt, ANCHOR.plusSeconds(30));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT2_CONFIRM");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(30L);
	}

	@Test
	void tickGapIsClampedToThirtySeconds() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 0L, ANCHOR);

		service.advance(attempt, ANCHOR.plusMinutes(10));

		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(30L);
		assertThat(attempt.getScenarioProgressUpdatedAt()).isEqualTo(ANCHOR.plusMinutes(10));
	}

	@Test
	void twoSecondTicksAccumulateWithoutDrift() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 0L, ANCHOR);

		for (int index = 1; index <= 6; index++) {
			service.advance(attempt, ANCHOR.plusSeconds(2L * index));
		}

		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(12L);
	}

	@Test
	void subSecondRemainderCarriesToTheNextTick() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 0L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(3).plusNanos(500_000_000L));
		service.advance(attempt, ANCHOR.plusSeconds(7));

		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(7L);
	}

	@Test
	void everySkippedVirtualMinuteIsSettledInOrder() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 0L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(30));

		ArgumentCaptor<LocalDateTime> pricedAt = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(settlementService, times(10)).settleCurrentRun(eq(ATTEMPT_ID), eq(1L), pricedAt.capture(),
			any(BigDecimal.class));
		assertThat(pricedAt.getAllValues()).isSorted().doesNotHaveDuplicates()
			.allSatisfy(at -> assertThat(at).isBetween(ANCHOR, ANCHOR.plusSeconds(30)));
		assertThat(pricedAt.getAllValues().get(9)).isEqualTo(ANCHOR.plusSeconds(30));
	}

	@Test
	void tickAfterTheScriptFinishedStillSettlesOnce() {
		PracticeAttempt attempt = startedAt("ACT4_CRASH", 60L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(30));

		verify(settlementService, times(1))
			.settleCurrentRun(eq(ATTEMPT_ID), eq(1L), eq(ANCHOR.plusSeconds(30)), any(BigDecimal.class));
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(60L);
	}

	@Test
	void tickWithoutElapsedTimeStillSettlesOnce() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 0L, ANCHOR);

		service.advance(attempt, ANCHOR);

		verify(settlementService, times(1)).settleCurrentRun(eq(ATTEMPT_ID), eq(1L), eq(ANCHOR), any(BigDecimal.class));
	}

	@Test
	void candleHighAndLowAccumulateAcrossSkippedMinutes() {
		PracticeAttempt attempt = startedAt("ACT2_RUMOR", 0L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(30));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT2_FAKEOUT");
		assertThat(attempt.getScenarioCandleLow()).isEqualByComparingTo(new BigDecimal("9750.00000000"));
		assertThat(attempt.getScenarioCandleHigh()).isEqualByComparingTo(new BigDecimal("10147.91000000"));
		assertThat(canonicalPriceService.canonicalPrice(attempt, ANCHOR.plusSeconds(30)))
			.isEqualByComparingTo(new BigDecimal("9888.41000000"));
	}

	@Test
	void progressStageRollsOverToTheNextStage() {
		PracticeAttempt attempt = startedAt("ACT1_RISE", 42L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(6));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT2_RUMOR");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(3L);
	}

	@Test
	void cursorBeyondAShortenedStageIsCleanedUpWithoutInflatingRemainingTime() {
		PracticeAttempt attempt = startedAt("ACT2_RUMOR", 30L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(3));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT2_FAKEOUT");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(3L);
		assertThat(attempt.getScenarioProgressUpdatedAt()).isEqualTo(ANCHOR.plusSeconds(3));
	}

	@Test
	void lastStageStopsAtFinishedAndKeepsTheFinalPrice() {
		PracticeAttempt attempt = startedAt("ACT4_CRASH", 57L, ANCHOR);

		service.advance(attempt, ANCHOR.plusSeconds(30));
		BigDecimal finalPrice = canonicalPriceService.canonicalPrice(attempt, ANCHOR.plusSeconds(30));
		service.advance(attempt, ANCHOR.plusSeconds(60));

		assertThat(attempt.getScenarioStageId()).isEqualTo("ACT4_CRASH");
		assertThat(attempt.getScenarioStageElapsedSeconds()).isEqualTo(60L);
		assertThat(canonicalPriceService.canonicalPrice(attempt, ANCHOR.plusSeconds(60)))
			.isEqualByComparingTo(finalPrice)
			.isEqualByComparingTo(CRYPTO_BASE_PRICE.multiply(new BigDecimal("0.790")));
	}

	@Test
	void versionOneAttemptIsUntouched() {
		PracticeAttempt attempt = legacyAttempt();

		service.advance(attempt, ANCHOR.plusSeconds(30));

		assertThat(attempt.getScenarioStageId()).isNull();
		verify(settlementService, never()).settleCurrentRun(anyLong(), anyLong(), any(LocalDateTime.class),
			any(BigDecimal.class));
	}

	private void holdNothing() {
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L)).thenReturn(BigDecimal.ZERO);
	}

	private void holdQuantity(String quantity) {
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L))
			.thenReturn(new BigDecimal(quantity));
	}

	private PracticeAttempt startedAt(String stageId, long elapsedSeconds, LocalDateTime progressUpdatedAt) {
		PracticeAttempt attempt = scenarioAttempt();
		attempt.startScenarioProgress(stageId, CRYPTO_BASE_PRICE, progressUpdatedAt);
		attempt.moveScenarioCursor(stageId, elapsedSeconds);
		return attempt;
	}

	private PracticeAttempt scenarioAttempt() {
		return attempt(TutorialPriceGenerator.VERSION_2);
	}

	private PracticeAttempt legacyAttempt() {
		return attempt(TutorialPriceGenerator.VERSION_1);
	}

	private PracticeAttempt attempt(short generatorVersion) {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, ANCHOR.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, ANCHOR);
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(
			instrument, ANCHOR, ANCHOR.toLocalDate(), 123_456_789L, generatorVersion, null, ANCHOR);
		return attempt;
	}
}
