package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PracticePendingExitPlanResponse(
	Long exitPlanId,
	BigDecimal stopLossRate,
	BigDecimal takeProfitRate,
	BigDecimal stopLossPrice,
	BigDecimal takeProfitPrice,
	BigDecimal entryPrice,
	BigDecimal quantity,
	LocalDateTime reservedAt) {

	public static PracticePendingExitPlanResponse from(ExitPlanResponse plan) {
		return plan == null
			? null
			: new PracticePendingExitPlanResponse(
				plan.id(),
				plan.stopLossRate(),
				plan.takeProfitRate(),
				plan.stopLossPrice(),
				plan.takeProfitPrice(),
				plan.entryPrice(),
				plan.quantity(),
				plan.reservedAt());
	}
}
