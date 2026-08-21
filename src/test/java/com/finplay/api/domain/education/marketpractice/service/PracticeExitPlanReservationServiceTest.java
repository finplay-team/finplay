// 사용자 주도 손절·익절 예약의 거부 판정과 진행 조회용 예약 상태 산출을 검증한다 (052 EXITFREE-020·021·022).
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

/**
 * 이 서비스가 지키는 것은 "언제 예약을 만들 수 있는가" 하나이고, 그 판정이 <b>진행 조회의
 * {@code exitPlanCreatable}과 같은 산출식</b>이라는 것이 계약의 핵심이다. 그래서 거부 케이스마다
 * {@code create}가 던지는 것과 {@code view}가 {@code creatable=false}를 주는 것을 함께 단언한다 — 한쪽만
 * 보면 화면이 연 버튼이 서버에서 409로 거부되는 상태를 놓친다.
 *
 * <p>원장이 얽히는 부분(예약 수량·tick 체결·재시작 정리)은 통합 테스트가 정본이다
 * ({@code PracticeExitPresetOcoIntegrationTest}·{@code PracticeOrderBasicsNoAutoExitIntegrationTest}).
 */
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
		// 042 자동 예약과 같은 경로다 — attempt·실행 세대 귀속이 붙어야 tick 정산·재시작 정리가 이 예약을 본다.
		assertThat(command.isPracticePath()).isTrue();
		assertThat(command.practiceOrigin().attemptId()).isEqualTo(ATTEMPT_ID);
		assertThat(command.practiceOrigin().runNumber()).isEqualTo(1L);
		// 기준가는 대본 canonical price다 — 엔진 기본 경로의 사인파 항시 시세가 아니다.
		assertThat(command.practiceOrigin().baselinePrice()).isEqualByComparingTo(ENTRY_PRICE);
		// 진입가는 그 진입의 체결가(snapshot)이고 수량은 실행 세대의 보유 전량이다.
		assertThat(command.priceInput().exitPriceType()).isEqualTo(ExitPriceType.PERCENT);
		assertThat(command.priceInput().entryPrice()).isEqualByComparingTo(ENTRY_PRICE);
		assertThat(command.priceInput().stopLossRate()).isEqualByComparingTo("2");
		assertThat(command.priceInput().takeProfitRate()).isEqualByComparingTo("8");
		assertThat(command.quantity()).isEqualByComparingTo(HELD);
		// 감사 해시는 자동 예약과 같은 식이라 write-once 판정이 두 경로를 함께 본다.
		assertThat(command.requestHash())
			.isEqualTo(ExitPlanPracticeOriginDto.auditRequestHash(ATTEMPT_ID, 1L, 1));
	}

	// 예약할 대상이 없으면 거부한다 — 매수 전에 예약을 걸면 무엇을 팔지가 정해지지 않는다.
	@Test
	void rejectsWhenNothingIsHeldInTheCurrentRun() {
		PracticeAttempt attempt = storyAttempt();
		when(practiceExitPlanQueryService.summarizeCurrentRun(ATTEMPT_ID, 1L))
			.thenReturn(PracticeRunExitPlanSummaryDto.empty());
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L)).thenReturn(BigDecimal.ZERO);

		assertRejected(attempt, ErrorCode.PRACTICE_STEP_LOCKED);
	}

	/**
	 * write-once — <b>취소된 예약도 센다.</b> 취소 후 더 낮은 선으로 다시 거는 것이 042 EXITPRESET-003이
	 * 막은 "손절선 슬금슬금 내리기"를 다른 문으로 되살리기 때문이다. 그래서 PENDING이 하나도 없는 상태로
	 * 고정한 뒤에도 거부돼야 한다 — PENDING만 보는 구현은 여기서만 틀린다.
	 */
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

	// 진입이 새로 열리면 그 진입 몫으로 다시 한 번 열린다 — 판정이 진입 단위라는 뜻이다.
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

	// 대본을 쓰지 않는 실행은 042 그대로 매수 체결이 자동으로 건다 — 두 경로가 공존하면 진입 하나에 예약이
	// 둘 생겨 042 EXITPRESET-020이 깨진다.
	@Test
	void rejectsRunsThatStillGetTheAutomaticReservation() {
		PracticeAttempt attempt = legacyAttempt();
		heldWithSnapshot(attempt, 1);

		assertRejected(attempt, ErrorCode.PRACTICE_STEP_LOCKED);
	}

	// 2단계 대본은 예약 자체를 두지 않는 자리다(049 ORDERBASICS-022) — 자동만 끄고 수동을 열어 두면
	// 그 대본에서 예약이 다른 문으로 되살아난다.
	@Test
	void rejectsTheOrderBasicsScript() {
		PracticeAttempt attempt = scriptAttempt(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		heldWithSnapshot(attempt, 1);

		assertRejected(attempt, ErrorCode.PRACTICE_STAGE_LOCKED);
	}

	/**
	 * 052 EXITFREE-022 — 다음에 권하는 쪽은 <b>먼저 겪은 쪽의 반대</b>다. 순서는 대본이 손절 먼저로
	 * 고정하지만 좁은 익절 폭을 건 사용자는 익절이 먼저 닿으므로, "손절 → 익절"로 하드코딩하지 않는다.
	 */
	@Test
	void recommendsTheOppositeOfWhicheverWasExperiencedFirst() {
		PracticeAttempt attempt = storyAttempt();
		// 겪음 판정은 예약을 지금 걸 수 있는지와 무관하다 — 손절로 청산돼 보유가 없는 상태에서도 나온다.
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

	// 화면이 취소 버튼을 그리려면 exitPlanId가 필요하다 — 예약 목록 API를 따로 부르지 않게 함께 싣는다.
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

	// 종목을 아직 고르지 않은 실행은 판정할 것이 없다 — 원장을 읽지 않고 "없음"을 준다.
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

	/** 거부는 {@code create}의 예외와 {@code view.creatable()==false}가 <b>함께</b> 성립해야 한다. */
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
		// **stub 안에서 stub하지 않는다** — Mockito가 UnfinishedStubbingException으로 터진다. 스냅샷 mock을
		// 먼저 완성해 두고 바깥 when에 값으로만 넘긴다.
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
