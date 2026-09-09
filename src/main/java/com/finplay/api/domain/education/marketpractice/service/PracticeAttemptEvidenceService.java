package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.entity.PracticeSellCause;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.PracticeExitPlanQueryService;
import com.finplay.api.domain.order.service.PracticeRunTradeSummaryDto;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptEvidenceService {

	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final TradeService tradeService;
	private final HoldingService holdingService;
	private final PracticeExitPlanQueryService practiceExitPlanQueryService;

	@Transactional(readOnly = true)
	public ResolvedPracticeAttemptEvidenceDto requireCurrentRun(
		PracticeAttempt attempt, Long userId, Long requiredHoldingId) {
		if (attempt.getInstrument() == null || !attempt.getInstrument().isTutorialSample()) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		PracticeRiskSnapshot observationBaseline = practiceRiskSnapshotRepository
			.findByAttemptIdAndRunNumberAndEntrySequence(
				attempt.getId(), attempt.getRunNumber(), PracticeRiskSnapshot.FIRST_ENTRY_SEQUENCE)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		validateBuyEvidence(attempt, userId, snapshot);

		Long holdingId = holdingService
			.findHoldingId(userId, attempt.getMarket(), attempt.getInstrument().getId())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		if (requiredHoldingId != null && !requiredHoldingId.equals(holdingId)) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}

		PracticeRunTradeSummaryDto tradeSummary = tradeService.summarizePracticeRun(
			attempt.getId(), attempt.getRunNumber());
		Trade sellTrade = tradeSummary.firstSellTrade();
		if (sellTrade != null && (!sellTrade.getAccount().getUser().getId().equals(userId)
			|| !sellTrade.getInstrument().getId().equals(attempt.getInstrument().getId()))) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		return new ResolvedPracticeAttemptEvidenceDto(
			snapshot, observationBaseline, holdingId, tradeSummary.buyQuantity(), tradeSummary.sellQuantity(),
			tradeSummary.remainingQuantity(), sellTrade, tradeSummary.averageBuyPrice(),
			tradeSummary.averageSellPrice(), tradeSummary.realizedPnl(), tradeSummary.soldBuyBasis(),
			resolveSellCause(attempt, sellTrade));
	}

	private PracticeSellCause resolveSellCause(PracticeAttempt attempt, Trade sellTrade) {
		if (sellTrade == null) {
			return null;
		}
		return PracticeSellCause.from(practiceExitPlanQueryService
			.findTriggeredSellOrderStatuses(attempt.getId(), attempt.getRunNumber())
			.get(sellTrade.getOrder().getId()));
	}

	private void validateBuyEvidence(
		PracticeAttempt attempt, Long userId, PracticeRiskSnapshot snapshot) {
		Trade buyTrade = snapshot.getBuyTrade();
		if (!buyTrade.getAccount().getUser().getId().equals(userId)
			|| !buyTrade.getInstrument().getId().equals(attempt.getInstrument().getId())
			|| !buyTrade.getOrder().getPracticeAttemptId().equals(attempt.getId())
			|| buyTrade.getOrder().getPracticeAttemptRunNumber() != attempt.getRunNumber()) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
	}
}
