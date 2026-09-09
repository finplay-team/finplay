package com.finplay.api.domain.order.dto.response;

import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.ExitPriceType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExitPlanResponse(
	Long id,
	Long holdingId,
	Long intentionId,
	Long buyTradeId,
	Long instrumentId,
	BigDecimal quantity,
	BigDecimal entryPrice,
	ExitPriceType exitPriceType,
	BigDecimal stopLossRate,
	BigDecimal takeProfitRate,
	BigDecimal stopLossPrice,
	BigDecimal takeProfitPrice,
	BigDecimal baselinePrice,
	LocalDateTime baselineObservedAt,
	ExitPlanStatus status,
	LocalDateTime reservedAt,
	LocalDateTime closedAt,
	Long triggeredOrderId,
	Long replaySessionId) {

	public static ExitPlanResponse from(ExitPlan plan) {
		return new ExitPlanResponse(
			plan.getId(),
			plan.getHolding().getId(),
			plan.getIntentionId(),
			plan.getBuyTrade() != null ? plan.getBuyTrade().getId() : null,
			plan.getInstrument().getId(),
			plan.getQuantity(),
			plan.getEntryPrice(),
			plan.getExitPriceType(),
			plan.getStopLossRate(),
			plan.getTakeProfitRate(),
			plan.getStopLossPrice(),
			plan.getTakeProfitPrice(),
			plan.getBaselinePrice(),
			plan.getBaselineObservedAt(),
			plan.getStatus(),
			plan.getReservedAt(),
			plan.getClosedAt(),
			plan.getTriggeredOrder() != null ? plan.getTriggeredOrder().getId() : null,
			plan.getReplaySession() != null ? plan.getReplaySession().getId() : null);
	}
}
