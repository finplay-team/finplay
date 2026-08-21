// 2단계 → 3단계 대본 전환 서비스의 거부 조건 5가지와 성공 경로(정리 호출 순서 포함)를 검증하는 단위 테스트
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.TutorialAccount;
import com.finplay.api.account.service.TutorialAccountService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.TutorialScenarioScriptId;
import com.finplay.api.order.service.PracticeOrderSettlementService;
import com.finplay.api.order.service.TradeService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeAttemptScriptAdvanceServiceTest {

	private static final Long USER_ID = 7L;
	private static final Long ATTEMPT_ID = 11L;
	private static final Long INSTRUMENT_ID = 21L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-21T06:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final PracticeAttemptRepository attemptRepository = mock(PracticeAttemptRepository.class);
	private final PracticeRiskSnapshotRepository riskSnapshotRepository = mock(PracticeRiskSnapshotRepository.class);
	private final PracticeStageProgressCalculationService stageProgressCalculationService = mock(
		PracticeStageProgressCalculationService.class);
	private final TradeService tradeService = mock(TradeService.class);
	private final PracticeOrderSettlementService orderSettlementService = mock(PracticeOrderSettlementService.class);
	private final TutorialAccountService tutorialAccountService = mock(TutorialAccountService.class);
	private final PracticeAttemptScriptAdvanceService service = new PracticeAttemptScriptAdvanceService(
		attemptRepository, riskSnapshotRepository, stageProgressCalculationService, tradeService,
		orderSettlementService, tutorialAccountService, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

	@Test
	void rejectsWhenAttemptAlreadyCompleted() {
		PracticeAttempt attempt = orderBasicsAttempt();
		ReflectionTestUtils.setField(attempt, "status", PracticeAttemptStatus.COMPLETED);
		stub(attempt);

		assertThatThrownBy(() -> service.advanceScript(USER_ID, Market.CRYPTO))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_ALREADY_COMPLETED));
		verifyNoInteractions(orderSettlementService);
	}

	@Test
	void rejectsWhenAttemptDoesNotUseScenarioScript() {
		PracticeAttempt attempt = legacyAttempt();
		stub(attempt);

		assertThatThrownBy(() -> service.advanceScript(USER_ID, Market.CRYPTO))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STAGE_LOCKED));
		verifyNoInteractions(orderSettlementService);
	}

	@Test
	void rejectsWhenAttemptAlreadyOnStoryScript() {
		PracticeAttempt attempt = storyAttempt();
		stub(attempt);

		assertThatThrownBy(() -> service.advanceScript(USER_ID, Market.CRYPTO))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STAGE_LOCKED));
		verifyNoInteractions(orderSettlementService);
	}

	@Test
	void rejectsWhenStageProgressIsIncomplete() {
		PracticeAttempt attempt = orderBasicsAttempt();
		stub(attempt);
		// 시장가 왕복은 마쳤지만 지정가 왕복은 아직이다.
		when(stageProgressCalculationService.calculate(attempt))
			.thenReturn(new PracticeStageProgressResponse(true, false, false));

		assertThatThrownBy(() -> service.advanceScript(USER_ID, Market.CRYPTO))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STAGE_LOCKED));
		verifyNoInteractions(orderSettlementService);
	}

	@Test
	void rejectsWhenCurrentRunHoldsAPositiveQuantity() {
		PracticeAttempt attempt = orderBasicsAttempt();
		stub(attempt);
		when(stageProgressCalculationService.calculate(attempt))
			.thenReturn(new PracticeStageProgressResponse(true, true, false));
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L)).thenReturn(new BigDecimal("0.1"));

		assertThatThrownBy(() -> service.advanceScript(USER_ID, Market.CRYPTO))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STAGE_LOCKED));
		verifyNoInteractions(orderSettlementService);
	}

	// 다섯 거부 조건을 전부 통과하면 예약·지정가 정리(순서: exitPlan → 지정가)가 먼저 불리고, 대본이
	// CRYPTO_STORY_V1로 바뀌며 커서가 지워진다. run·튜토리얼 계좌는 이 서비스가 손대지 않는다(plan §3).
	@Test
	void advancesScriptAndCancelsExitPlansBeforeLimitOrdersWhenAllConditionsPass() {
		PracticeAttempt attempt = orderBasicsAttempt();
		stub(attempt);
		when(stageProgressCalculationService.calculate(attempt))
			.thenReturn(new PracticeStageProgressResponse(true, true, false));
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L)).thenReturn(BigDecimal.ZERO);
		when(riskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		TutorialAccount account = mock(TutorialAccount.class);
		when(account.getCashBalance()).thenReturn(10_000_000L);
		when(account.getAvailableCash()).thenReturn(10_000_000L);
		when(account.getRealizedPnl()).thenReturn(0L);
		when(tutorialAccountService.find(USER_ID, com.finplay.api.account.domain.Market.CRYPTO))
			.thenReturn(Optional.of(account));

		PracticeAttemptResponse response = service.advanceScript(USER_ID, Market.CRYPTO);

		assertThat(response.runNumber()).isEqualTo(1L);
		assertThat(response.tutorialCashBalance()).isEqualTo(10_000_000L);
		assertThat(response.exitPresetLocked()).isFalse();
		assertThat(attempt.scenarioScriptId()).isEqualTo(TutorialScenarioScriptId.CRYPTO_STORY_V1);
		assertThat(attempt.getScenarioStageId()).isNull();
		InOrder inOrder = Mockito.inOrder(orderSettlementService);
		inOrder.verify(orderSettlementService).cancelCurrentRunExitPlans(USER_ID, ATTEMPT_ID, 1L);
		inOrder.verify(orderSettlementService).cancelCurrentRunPendingLimitOrders(USER_ID, ATTEMPT_ID, 1L);
	}

	private void stub(PracticeAttempt attempt) {
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
	}

	private static Instrument instrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrument;
	}

	private static PracticeAttempt orderBasicsAttempt() {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		attempt.selectInstrument(instrument(), NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)2,
			TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1, NOW.minusMinutes(10));
		return attempt;
	}

	private static PracticeAttempt storyAttempt() {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		attempt.selectInstrument(instrument(), NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)2,
			TutorialScenarioScriptId.CRYPTO_STORY_V1, NOW.minusMinutes(10));
		return attempt;
	}

	private static PracticeAttempt legacyAttempt() {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		attempt.selectInstrument(instrument(), NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)1, null,
			NOW.minusMinutes(10));
		return attempt;
	}
}
