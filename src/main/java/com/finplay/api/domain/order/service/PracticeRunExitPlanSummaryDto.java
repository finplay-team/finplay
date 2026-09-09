package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.dto.response.ExitPlanResponse;

public record PracticeRunExitPlanSummaryDto(
	boolean stopLossFilled, boolean takeProfitFilled, ExitPlanResponse pendingPlan) {

	public static PracticeRunExitPlanSummaryDto empty() {
		return new PracticeRunExitPlanSummaryDto(false, false, null);
	}
}
