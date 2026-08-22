// 시장가 매수·매도 주문 생성 결과(주문+체결+실현손익)를 노출하는 응답 DTO
package com.finplay.api.domain.order.dto.response;

import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.Trade;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResponse(
	Long orderId,
	String market,
	Long instrumentId,
	String side,
	String orderType,
	String status,
	BigDecimal quantity,
	LocalDateTime requestedAt,
	Long tradeId,
	BigDecimal price,
	long amount,
	long fee,
	Long realizedPnl,
	LocalDateTime executedAt) {

	public static OrderResponse of(Order order, Trade trade) {
		return new OrderResponse(
			order.getId(),
			order.getInstrument().getMarket().name(),
			order.getInstrument().getId(),
			order.getSide().name(),
			order.getOrderType().name(),
			order.getStatus().name(),
			order.getQuantity(),
			order.getRequestedAt(),
			trade.getId(),
			trade.getPrice(),
			trade.getAmount(),
			trade.getFee(),
			trade.getRealizedPnl(),
			trade.getExecutedAt());
	}
}
