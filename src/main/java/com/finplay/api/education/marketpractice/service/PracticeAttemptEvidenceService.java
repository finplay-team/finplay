// favorite·intention 없이 현재 attempt/run의 snapshot·주문 원장·holding을 재해석하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.education.marketpractice.domain.PracticeSellCause;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.order.domain.ExitPlanStatus;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.PracticeExitPlanQueryService;
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
	private final PracticeExitPlanQueryService practiceExitPlanQueryService;

	@Transactional(readOnly = true)
	public ResolvedPracticeAttemptEvidenceDto requireCurrentRun(
		PracticeAttempt attempt, Long userId, Long requiredHoldingId) {
		if (attempt.getInstrument() == null || !attempt.getInstrument().isTutorialSample()) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		// 최신 진입 — 화면 기준선 표시와 매수 evidence 검증(지금 진입의 체결이 내 것인가)에 쓴다.
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber())
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));
		// 첫 진입 — 관찰 필터 기준선. 이 자리에 최신 진입을 쓰면 재매수 순간 이전 관찰이 사라진다.
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

	// 042 EXITPRESET-008 — 예약이 발동시킨 매도 주문인지 되짚는다. 예약이 가리키지 않는 매도는 전부 MANUAL
	// 이며, 예약 자체가 없는 STOCK 튜토리얼과 기능 도입 전 실행도 여기 들어간다.
	private PracticeSellCause resolveSellCause(PracticeAttempt attempt, Trade sellTrade) {
		if (sellTrade == null) {
			return null;
		}
		ExitPlanStatus status = practiceExitPlanQueryService
			.findTriggeredSellOrderStatuses(attempt.getId(), attempt.getRunNumber())
			.get(sellTrade.getOrder().getId());
		if (status == ExitPlanStatus.FILLED_STOP_LOSS) {
			return PracticeSellCause.STOP_LOSS;
		}
		if (status == ExitPlanStatus.FILLED_TAKE_PROFIT) {
			return PracticeSellCause.TAKE_PROFIT;
		}
		return PracticeSellCause.MANUAL;
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
