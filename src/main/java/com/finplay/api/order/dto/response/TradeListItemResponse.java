// 체결 내역 목록 항목 한 건을 노출하는 응답 DTO(체결 정보 전용)
package com.finplay.api.order.dto.response;

import com.finplay.api.order.domain.Trade;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TradeListItemResponse(
	Long tradeId,
	Long instrumentId,
	String side,
	BigDecimal price,
	BigDecimal quantity,
	long amount,
	long fee,
	Long realizedPnl,
	LocalDateTime executedAt) {

	public static TradeListItemResponse from(Trade trade) {
		return new TradeListItemResponse(
			trade.getId(),
			trade.getInstrument().getId(),
			trade.getSide().name(),
			trade.getPrice(),
			trade.getQuantity(),
			trade.getAmount(),
			trade.getFee(),
			trade.getRealizedPnl(),
			trade.getExecutedAt());
	}
}
