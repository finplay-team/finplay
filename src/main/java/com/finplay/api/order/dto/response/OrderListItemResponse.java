// 내 주문 목록 조회 결과 한 건을 노출하는 응답 DTO(체결 전용 필드 미포함)
package com.finplay.api.order.dto.response;

import com.finplay.api.order.domain.Order;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderListItemResponse(
	Long orderId,
	String market,
	Long instrumentId,
	String side,
	String orderType,
	String status,
	BigDecimal quantity,
	LocalDateTime requestedAt) {

	public static OrderListItemResponse from(Order order) {
		return new OrderListItemResponse(
			order.getId(),
			order.getInstrument().getMarket().name(),
			order.getInstrument().getId(),
			order.getSide().name(),
			order.getOrderType().name(),
			order.getStatus().name(),
			order.getQuantity(),
			order.getRequestedAt());
	}
}
