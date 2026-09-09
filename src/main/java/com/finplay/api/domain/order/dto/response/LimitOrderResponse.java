package com.finplay.api.domain.order.dto.response;

import com.finplay.api.domain.order.entity.Order;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record LimitOrderResponse(
	Long orderId,
	String market,
	Long instrumentId,
	String side,
	String orderType,
	String status,
	BigDecimal quantity,
	BigDecimal limitPrice,
	LocalDateTime requestedAt) {

	public static LimitOrderResponse from(Order order) {
		return new LimitOrderResponse(
			order.getId(),
			order.getInstrument().getMarket().name(),
			order.getInstrument().getId(),
			order.getSide().name(),
			order.getOrderType().name(),
			order.getStatus().name(),
			order.getQuantity(),
			order.getLimitPrice(),
			order.getRequestedAt());
	}
}
