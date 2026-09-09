package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.entity.PracticeSellCause;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.ExitPriceType;
import com.finplay.api.domain.order.service.ExitPlanCreateCommandDto;
import com.finplay.api.domain.order.service.ExitPlanCreationService;
import com.finplay.api.domain.order.service.ExitPlanPracticeOriginDto;
import com.finplay.api.domain.order.service.PracticeExitPlanQueryService;
import com.finplay.api.domain.order.service.PracticeRunExitPlanSummaryDto;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeExitPlanReservationServiceTest {

	private static final long USER_ID = 7L;
	private static final long ATTEMPT_ID = 11L;
	private static final long INSTRUMENT_ID = 9L;
	private static final long HOLDING_ID = 77L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 10, 0);
	private static final BigDecimal ENTRY_PRICE = new BigDecimal("10000.00000000");
	private static final BigDecimal HELD = new BigDecimal("0.5");

	private final PracticeAttemptRepository practiceAttemptRepository = mock(PracticeAttemptRepository.class);
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository = mock(
		PracticeRiskSnapshotRepository.class);
	private final PracticeExitPlanQueryService practiceExitPlanQueryService = mock(PracticeExitPlanQueryService.class);
	private final PracticeAttemptCanonicalPriceService canonicalPriceService = mock(
		PracticeAttemptCanonicalPriceService.class);
	private final ExitPlanCreationService exitPlanCreationService = mock(ExitPlanCreationService.class);
	private final TradeService tradeService = mock(TradeService.class);
	private final HoldingService holdingService = mock(HoldingService.class);

	private final PracticeExitPlanReservationService service = new PracticeExitPlanReservationService(
		practiceAttemptRepository, practiceRiskSnapshotRepository, practiceExitPlanQueryService,
		canonicalPriceService, exitPlanCreationService, tradeService, holdingService,
		Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));

	@Test
	void createsTheReservationForTheWholeHeldQuantityAtTheEntryFillPrice() {
		PracticeAttempt attempt = storyAttempt();
		heldWithSnapshot(attempt, 1);
		when(exitPlanCreationService.create(any()))
			.thenReturn(mock(ExitPlan.class, org.mockito.Answers.RETURNS_DEEP_STUBS));

		service.create(USER_ID, Market.CRYPTO, ExitRates.of(new BigDecimal("2"), new BigDecimal("8")));

		ArgumentCaptor<ExitPlanCreateCommandDto> captor = ArgumentCaptor.forClass(ExitPlanCreateCommandDto.class);
		verify(exitPlanCreationService).create(captor.capture());
		ExitPlanCreateCommandDto command = captor.getValue();
		assertThat(command.isPracticePath()).isTrue();
		assertThat(command.practiceOrigin().attemptId()).isEqualTo(ATTEMPT_ID);
		assertThat(command.practiceOrigin().runNumber()).isEqualTo(1L);
		assertThat(command.practiceOrigin().baselinePrice()).isEqualByComparingTo(ENTRY_PRICE);
		assertThat(command.priceInput().exitPriceType()).isEqualTo(ExitPriceType.PERCENT);
		assertThat(command.priceInput().entryPrice()).isEqualByComparingTo(ENTRY_PRICE);
		assertThat(command.priceInput().stopLossRate()).isEqualByComparingTo("2");
		assertThat(command.priceInput().takeProfitRate()).isEqualByComparingTo("8");
		assertThat(command.quantity()).isEqualByComparingTo(HELD);
		assertThat(command.requestHash())
			.isEqualTo(ExitPlanPracticeOriginDto.auditRequestHash(ATTEMPT_ID, 1L, 1));
	}

	@Test
	void rejectsWhenNothingIsHeldInTheCurrentRun() {
		PracticeAttempt attempt = storyAttempt();
		when(practiceExitPlanQueryService.summarizeCurrentRun(ATTEMPT_ID, 1L))
			.thenReturn(PracticeRunExitPlanSummaryDto.empty());
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L)).thenReturn(BigDecimal.ZERO);

		assertRejected(attempt, ErrorCode.PRACTICE_STEP_LOCKED);
	}

	@Test
	void rejectsASecondReservationForTheSameEntryEvenAfterItWasCancelled() {
		PracticeAttempt attempt = storyAttempt();
		heldWithSnapshot(attempt, 1);
		when(practiceExitPlanQueryService.summarizeCurrentRun(ATTEMPT_ID, 1L))
			.thenReturn(PracticeRunExitPlanSummaryDto.empty());
		when(practiceExitPlanQueryService.existsEntryReservation(anyLong(), anyLong(), anyString()))
			.thenReturn(true);

		assertRejected(attempt, ErrorCode.EXIT_PLAN_ALREADY_EXISTS);
	}

	@Test
	void allowsAReservationAgainForANewEntry() {
		PracticeAttempt attempt = storyAttempt();
		heldWithSnapshot(attempt, 2);
		when(practiceExitPlanQueryService.existsEntryReservation(
			ATTEMPT_ID, 1L, ExitPlanPracticeOriginDto.auditRequestHash(ATTEMPT_ID, 1L, 1))).thenReturn(true);
		when(practiceExitPlanQueryService.existsEntryReservation(
			ATTEMPT_ID, 1L, ExitPlanPracticeOriginDto.auditRequestHash(ATTEMPT_ID, 1L, 2))).thenReturn(false);

		assertThat(service.view(attempt).creatable()).isTrue();
	}

	@Test
	void rejectsRunsThatStillGetTheAutomaticReservation() {
		PracticeAttempt attempt = legacyAttempt();
		heldWithSnapshot(attempt, 1);

		assertRejected(attempt, ErrorCode.PRACTICE_STEP_LOCKED);
	}

	@Test
	void rejectsTheOrderBasicsScript() {
		PracticeAttempt attempt = scriptAttempt(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		heldWithSnapshot(attempt, 1);

		assertRejected(attempt, ErrorCode.PRACTICE_STAGE_LOCKED);
	}

	@Test
	void runsWithoutTheUserDrivenPathNeverWaitForAReservation() {
		assertThat(service.entryReservationSatisfied(legacyAttempt())).isTrue();
		assertThat(service.entryReservationSatisfied(scriptAttempt(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1)))
			.isTrue();
		verifyNoInteractions(practiceRiskSnapshotRepository, practiceExitPlanQueryService);
	}

	@Test
	void theStoryScriptWaitsForTheReservationOfTheCurrentEntry() {
		PracticeAttempt attempt = storyAttempt();
		PracticeRiskSnapshot secondEntry = snapshot(2);
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(secondEntry));
		when(practiceExitPlanQueryService.existsEntryReservation(
			ATTEMPT_ID, 1L, ExitPlanPracticeOriginDto.auditRequestHash(ATTEMPT_ID, 1L, 1))).thenReturn(true);

		assertThat(service.entryReservationSatisfied(attempt)).isFalse();

		when(practiceExitPlanQueryService.existsEntryReservation(
			ATTEMPT_ID, 1L, ExitPlanPracticeOriginDto.auditRequestHash(ATTEMPT_ID, 1L, 2))).thenReturn(true);
		assertThat(service.entryReservationSatisfied(attempt)).isTrue();
	}

	@Test
	void aBrokenLedgerWithoutAnEntryBaselineDoesNotTrapTheUser() {
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());

		assertThat(service.entryReservationSatisfied(storyAttempt())).isTrue();
	}

	@Test
	void recommendsTheOppositeOfWhicheverWasExperiencedFirst() {
		PracticeAttempt attempt = storyAttempt();
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L)).thenReturn(BigDecimal.ZERO);

		when(practiceExitPlanQueryService.summarizeCurrentRun(ATTEMPT_ID, 1L))
			.thenReturn(new PracticeRunExitPlanSummaryDto(true, false, null));
		assertThat(service.view(attempt).experience()).satisfies(experience -> {
			assertThat(experience.stopLossExperienced()).isTrue();
			assertThat(experience.bothExperienced()).isFalse();
			assertThat(experience.recommendedNext()).isEqualTo(PracticeSellCause.TAKE_PROFIT);
		});

		when(practiceExitPlanQueryService.summarizeCurrentRun(ATTEMPT_ID, 1L))
			.thenReturn(new PracticeRunExitPlanSummaryDto(false, true, null));
		assertThat(service.view(attempt).experience().recommendedNext()).isEqualTo(PracticeSellCause.STOP_LOSS);

		when(practiceExitPlanQueryService.summarizeCurrentRun(ATTEMPT_ID, 1L))
			.thenReturn(new PracticeRunExitPlanSummaryDto(true, true, null));
		assertThat(service.view(attempt).experience()).satisfies(experience -> {
			assertThat(experience.bothExperienced()).isTrue();
			assertThat(experience.recommendedNext()).isNull();
		});

		when(practiceExitPlanQueryService.summarizeCurrentRun(ATTEMPT_ID, 1L))
			.thenReturn(PracticeRunExitPlanSummaryDto.empty());
		assertThat(service.view(attempt).experience().recommendedNext()).isNull();
	}

	@Test
	void exposesThePendingReservationWithTheIdNeededToCancelIt() {
		PracticeAttempt attempt = storyAttempt();
		heldWithSnapshot(attempt, 1);
		when(practiceExitPlanQueryService.summarizeCurrentRun(ATTEMPT_ID, 1L))
			.thenReturn(new PracticeRunExitPlanSummaryDto(false, false, pendingPlanResponse()));
		when(practiceExitPlanQueryService.existsEntryReservation(anyLong(), anyLong(), anyString()))
			.thenReturn(true);

		PracticeExitPlanViewDto view = service.view(attempt);

		assertThat(view.creatable()).isFalse();
		assertThat(view.pendingExitPlan()).isNotNull().satisfies(pending -> {
			assertThat(pending.exitPlanId()).isEqualTo(41L);
			assertThat(pending.stopLossRate()).isEqualByComparingTo("2");
			assertThat(pending.takeProfitRate()).isEqualByComparingTo("8");
			assertThat(pending.stopLossPrice()).isEqualByComparingTo("9800.00000000");
			assertThat(pending.takeProfitPrice()).isEqualByComparingTo("10800.00000000");
			assertThat(pending.quantity()).isEqualByComparingTo(HELD);
		});
	}

	@Test
	void viewReturnsTheEmptyStateBeforeAnInstrumentIsChosen() {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);

		PracticeExitPlanViewDto view = service.view(attempt);

		assertThat(view.creatable()).isFalse();
		assertThat(view.pendingExitPlan()).isNull();
		assertThat(view.experience().stopLossExperienced()).isFalse();
		verifyNoInteractions(practiceExitPlanQueryService);
	}

	private void assertRejected(PracticeAttempt attempt, ErrorCode expected) {
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));

		assertThatThrownBy(() -> service.create(
			USER_ID, Market.CRYPTO, ExitRates.of(new BigDecimal("3"), new BigDecimal("5"))))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
		verifyNoInteractions(exitPlanCreationService);
		assertThat(service.view(attempt).creatable()).isFalse();
	}

	private void heldWithSnapshot(PracticeAttempt attempt, int entrySequence) {
		PracticeRiskSnapshot snapshot = snapshot(entrySequence);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L)).thenReturn(HELD);
		when(practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.of(snapshot));
		when(practiceExitPlanQueryService.summarizeCurrentRun(ATTEMPT_ID, 1L))
			.thenReturn(PracticeRunExitPlanSummaryDto.empty());
		when(canonicalPriceService.canonicalPrice(attempt, NOW)).thenReturn(ENTRY_PRICE);
		when(holdingService.findHoldingId(USER_ID, Market.CRYPTO, INSTRUMENT_ID))
			.thenReturn(Optional.of(HOLDING_ID));
		Holding holding = mock(Holding.class);
		Account account = mock(Account.class);
		User user = mock(User.class);
		when(account.getUser()).thenReturn(user);
		when(holding.getAccount()).thenReturn(account);
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
	}

	private static PracticeRiskSnapshot snapshot(int entrySequence) {
		PracticeRiskSnapshot snapshot = mock(PracticeRiskSnapshot.class);
		when(snapshot.getEntrySequence()).thenReturn(entrySequence);
		when(snapshot.getEntryPrice()).thenReturn(ENTRY_PRICE);
		return snapshot;
	}

	private static ExitPlanResponse pendingPlanResponse() {
		return new ExitPlanResponse(
			41L, HOLDING_ID, null, null, INSTRUMENT_ID, HELD, ENTRY_PRICE, ExitPriceType.PERCENT,
			new BigDecimal("2"), new BigDecimal("8"), new BigDecimal("9800.00000000"),
			new BigDecimal("10800.00000000"), ENTRY_PRICE, NOW, ExitPlanStatus.PENDING, NOW, null, null, null);
	}

	private static PracticeAttempt storyAttempt() {
		return scriptAttempt(TutorialScenarioScriptId.CRYPTO_STORY_V1);
	}

	private static PracticeAttempt scriptAttempt(TutorialScenarioScriptId scriptId) {
		return attempt((short)2, scriptId);
	}

	private static PracticeAttempt legacyAttempt() {
		return attempt((short)1, null);
	}

	private static PracticeAttempt attempt(short generatorVersion, TutorialScenarioScriptId scriptId) {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		attempt.selectInstrument(
			instrument(), NOW.minusMinutes(10), LocalDate.of(2026, 8, 21), 123L, generatorVersion, scriptId,
			NOW.minusMinutes(10));
		return attempt;
	}

	private static Instrument instrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUT", "튜토리얼 샘플", BigDecimal.ONE, 0L, true, NOW.minusDays(1));
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrument;
	}
}
