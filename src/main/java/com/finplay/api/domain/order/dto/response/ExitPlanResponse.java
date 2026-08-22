// OCO 손절·익절 예약 단건 응답 DTO — 일반·교육 두 경로가 공유한다(021 plan.md "응답 계약")
package com.finplay.api.domain.order.dto.response;

import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.ExitPriceType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@code holdingId}는 두 경로 모두 항상 non-null이다. {@code intentionId}·{@code buyTradeId}는 일반 경로에서
 * null이다(021 plan.md).
 */
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
