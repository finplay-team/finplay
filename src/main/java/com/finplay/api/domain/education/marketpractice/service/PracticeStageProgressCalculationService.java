package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.service.PracticeExitPlanQueryService;
import com.finplay.api.domain.order.service.PracticeRunFillKindDto;
import com.finplay.api.domain.order.service.TradeService;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeStageProgressCalculationService {

	private final TradeService tradeService;
	private final PracticeExitPlanQueryService practiceExitPlanQueryService;

	@Transactional(readOnly = true)
	public PracticeStageProgressResponse calculate(PracticeAttempt attempt) {
		if (attempt.getInstrument() == null) {
			return PracticeStageProgressResponse.none();
		}
		Long attemptId = attempt.getId();
		long runNumber = attempt.getRunNumber();

		Set<Long> triggeredSellOrderIds = practiceExitPlanQueryService
			.findTriggeredSellOrderStatuses(attemptId, runNumber)
			.keySet();
		List<PracticeRunFillKindDto> fills = tradeService.findPracticeRunFillKinds(attemptId, runNumber);

		return new PracticeStageProgressResponse(
			roundTripCompleted(fills, triggeredSellOrderIds, OrderType.MARKET),
			roundTripCompleted(fills, triggeredSellOrderIds, OrderType.LIMIT),
			exitStandardChosen(attempt));
	}

	private boolean exitStandardChosen(PracticeAttempt attempt) {
		if (attempt.exitRatesSelected()) {
			return true;
		}
		if (attempt.scenarioScriptId() == null) {
			return false;
		}
		return practiceExitPlanQueryService.existsRunReservation(attempt.getId(), attempt.getRunNumber());
	}

	private boolean roundTripCompleted(
		List<PracticeRunFillKindDto> fills, Set<Long> triggeredSellOrderIds, OrderType orderType) {
		boolean bought = false;
		boolean sold = false;
		for (PracticeRunFillKindDto fill : fills) {
			if (fill.orderType() != orderType) {
				continue;
			}
			if (fill.side() == OrderSide.BUY) {
				bought = true;
			} else if (!triggeredSellOrderIds.contains(fill.orderId())) {
				sold = true;
			}
		}
		return bought && sold;
	}

}
