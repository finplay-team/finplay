// favorite·intention 없이 현재 attempt/run의 snapshot·주문 원장·holding을 재해석하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.PracticeRunTradeSummaryDto;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.portfolio.service.HoldingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptEvidenceService {

	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final TradeService tradeService;
	private final HoldingService holdingService;

	@Transactional(readOnly = true)
	public ResolvedPracticeAttemptEvidenceDto requireCurrentRun(
		PracticeAttempt attempt, Long userId, Long requiredHoldingId) {
		if (attempt.getInstrument() == null || !attempt.getInstrument().isTutorialSample()) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findByAttemptIdAndRunNumber(attempt.getId(), attempt.getRunNumber())
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
			snapshot, holdingId, tradeSummary.buyQuantity(), tradeSummary.sellQuantity(),
			tradeSummary.remainingQuantity(), sellTrade, tradeSummary.averageBuyPrice(),
			tradeSummary.averageSellPrice(), tradeSummary.realizedPnl(), tradeSummary.soldBuyBasis());
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
