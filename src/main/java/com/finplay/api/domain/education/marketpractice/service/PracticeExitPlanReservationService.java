package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeExitExperienceResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticePendingExitPlanResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.service.ExitPlanCreateCommandDto;
import com.finplay.api.domain.order.service.ExitPlanCreationService;
import com.finplay.api.domain.order.service.ExitPlanPracticeOriginDto;
import com.finplay.api.domain.order.service.ExitPriceInputDto;
import com.finplay.api.domain.order.service.PracticeExitPlanQueryService;
import com.finplay.api.domain.order.service.PracticeRunExitPlanSummaryDto;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeExitPlanReservationService {

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final PracticeExitPlanQueryService practiceExitPlanQueryService;
	private final PracticeAttemptCanonicalPriceService canonicalPriceService;
	private final ExitPlanCreationService exitPlanCreationService;
	private final TradeService tradeService;
	private final HoldingService holdingService;
	private final Clock clock;

	@Transactional
	public ExitPlanResponse create(Long userId, Market market, ExitRates exitRates) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		ReservationState state = load(attempt);
		if (state.rejection() != null) {
			throw new BusinessException(state.rejection());
		}

		Holding holding = requireHolding(attempt);
		PracticeRiskSnapshot snapshot = state.snapshot();

		ExitPriceInputDto priceInput = ExitPriceInputDto.ofPercent(
			snapshot.getEntryPrice(), exitRates.stopLossRate(), exitRates.takeProfitRate());
		ExitPlan plan = exitPlanCreationService.create(ExitPlanCreateCommandDto.practice(
			holding.getAccount().getUser(),
			holding,
			state.heldQuantity(),
			priceInput,
			state.requestHash(),
			new ExitPlanPracticeOriginDto(
				attempt.getId(),
				attempt.getRunNumber(),
				canonicalPriceService.canonicalPrice(attempt, LocalDateTime.now(clock)))));
		return ExitPlanResponse.from(plan);
	}

	@Transactional(readOnly = true)
	public PracticeExitPlanViewDto view(PracticeAttempt attempt) {
		if (attempt.getInstrument() == null) {
			return PracticeExitPlanViewDto.none();
		}
		ReservationState state = load(attempt);
		return new PracticeExitPlanViewDto(
			state.rejection() == null,
			PracticePendingExitPlanResponse.from(state.summary().pendingPlan()),
			PracticeExitExperienceResponse.from(state.summary()));
	}

	@Transactional(readOnly = true)
	public boolean entryReservationSatisfied(PracticeAttempt attempt) {
		if (pathRejection(attempt) != null) {
			return true;
		}
		return practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber())
			.map(snapshot -> practiceExitPlanQueryService.existsEntryReservation(
				attempt.getId(), attempt.getRunNumber(), entryRequestHash(attempt, snapshot)))
			.orElse(true);
	}

	private ReservationState load(PracticeAttempt attempt) {
		PracticeRunExitPlanSummaryDto summary = practiceExitPlanQueryService
			.summarizeCurrentRun(attempt.getId(), attempt.getRunNumber());
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			return ReservationState.rejected(summary, ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		if (attempt.getStatus() != PracticeAttemptStatus.IN_PROGRESS || attempt.getInstrument() == null) {
			return ReservationState.rejected(summary, ErrorCode.PRACTICE_STEP_LOCKED);
		}
		ErrorCode pathRejection = pathRejection(attempt);
		if (pathRejection != null) {
			return ReservationState.rejected(summary, pathRejection);
		}
		BigDecimal heldQuantity = tradeService.netFilledQuantity(attempt.getId(), attempt.getRunNumber());
		if (heldQuantity.signum() <= 0) {
			return ReservationState.rejected(summary, ErrorCode.PRACTICE_STEP_LOCKED);
		}
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber())
			.orElse(null);
		if (snapshot == null) {
			return ReservationState.rejected(summary, ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		String requestHash = entryRequestHash(attempt, snapshot);
		if (practiceExitPlanQueryService.existsEntryReservation(
			attempt.getId(), attempt.getRunNumber(), requestHash)) {
			return ReservationState.rejected(summary, ErrorCode.EXIT_PLAN_ALREADY_EXISTS);
		}
		return new ReservationState(summary, heldQuantity, snapshot, requestHash, null);
	}

	private ErrorCode pathRejection(PracticeAttempt attempt) {
		TutorialScenarioScriptId scriptId = attempt.scenarioScriptId();
		if (scriptId == null) {
			return ErrorCode.PRACTICE_STEP_LOCKED;
		}
		return scriptId == TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1 ? ErrorCode.PRACTICE_STAGE_LOCKED : null;
	}

	private static String entryRequestHash(PracticeAttempt attempt, PracticeRiskSnapshot snapshot) {
		return ExitPlanPracticeOriginDto.auditRequestHash(
			attempt.getId(), attempt.getRunNumber(), snapshot.getEntrySequence());
	}

	private Holding requireHolding(PracticeAttempt attempt) {
		Long holdingId = holdingService
			.findHoldingId(attempt.getUserId(), attempt.getMarket(), attempt.getInstrument().getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		return holdingService.findHoldingForOwner(attempt.getUserId(), holdingId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
	}

	private record ReservationState(
		PracticeRunExitPlanSummaryDto summary,
		BigDecimal heldQuantity,
		PracticeRiskSnapshot snapshot,
		String requestHash,
		ErrorCode rejection) {

		static ReservationState rejected(PracticeRunExitPlanSummaryDto summary, ErrorCode rejection) {
			return new ReservationState(summary, null, null, null, rejection);
		}
	}
}
