package com.finplay.api.domain.education.marketpractice.entity;

import com.finplay.api.domain.order.entity.ExitPlanStatus;

public enum PracticeSellCause {
	STOP_LOSS,
	TAKE_PROFIT,
	MANUAL;

	public static PracticeSellCause from(ExitPlanStatus triggeredExitPlanStatus) {
		if (triggeredExitPlanStatus == ExitPlanStatus.FILLED_STOP_LOSS) {
			return STOP_LOSS;
		}
		if (triggeredExitPlanStatus == ExitPlanStatus.FILLED_TAKE_PROFIT) {
			return TAKE_PROFIT;
		}
		return MANUAL;
	}
}
