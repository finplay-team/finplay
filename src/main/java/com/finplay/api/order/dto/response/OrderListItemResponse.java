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
	BigDecimal limitPrice,
	LocalDateTime requestedAt,
	Long practiceAttemptId,
	Long practiceAttemptRunNumber) {

	// PR #237 리뷰 차단 반영: limitPrice가 없으면 미체결 지정가 목록(GET /api/orders/pending)에서 "얼마에
	// 걸어둔 주문인지" 알 수 없다. 시장가 주문은 Order.limitPrice가 애초에 null이라 그대로 null로 나간다.
	public static OrderListItemResponse from(Order order) {
		return new OrderListItemResponse(
			order.getId(),
			order.getInstrument().getMarket().name(),
			order.getInstrument().getId(),
			order.getSide().name(),
			order.getOrderType().name(),
			order.getStatus().name(),
			order.getQuantity(),
			order.getLimitPrice(),
			order.getRequestedAt(),
			order.getPracticeAttemptId(),
			order.getPracticeAttemptRunNumber());
	}
}
