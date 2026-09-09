package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.education.marketpractice.entity.PracticeSellCause;
import com.finplay.api.domain.order.service.PracticeRunExitPlanSummaryDto;

public record PracticeExitExperienceResponse(
	boolean stopLossExperienced,
	boolean takeProfitExperienced,
	boolean bothExperienced,
	PracticeSellCause recommendedNext) {

	private static final PracticeExitExperienceResponse NONE = new PracticeExitExperienceResponse(false, false, false,
		null);

	public static PracticeExitExperienceResponse none() {
		return NONE;
	}

	public static PracticeExitExperienceResponse from(PracticeRunExitPlanSummaryDto summary) {
		boolean stopLoss = summary.stopLossFilled();
		boolean takeProfit = summary.takeProfitFilled();
		return new PracticeExitExperienceResponse(
			stopLoss, takeProfit, stopLoss && takeProfit, recommendedNext(stopLoss, takeProfit));
	}

	private static PracticeSellCause recommendedNext(boolean stopLoss, boolean takeProfit) {
		if (stopLoss == takeProfit) {
			return null;
		}
		return stopLoss ? PracticeSellCause.TAKE_PROFIT : PracticeSellCause.STOP_LOSS;
	}
}
