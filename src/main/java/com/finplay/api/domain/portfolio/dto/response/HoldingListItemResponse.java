package com.finplay.api.domain.portfolio.dto.response;

import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.HoldingValuationDto;
import java.math.BigDecimal;

public record HoldingListItemResponse(
	Long holdingId,
	Long instrumentId,
	String symbol,
	String name,
	BigDecimal quantity,
	BigDecimal reservedQuantity,
	BigDecimal averagePrice,
	BigDecimal currentPrice,
	Long evaluationAmount,
	Long unrealizedPnl,
	BigDecimal returnRate,
	String priceStatus) {

	public static HoldingListItemResponse of(Holding holding, HoldingValuationDto valuation) {
		return new HoldingListItemResponse(
			holding.getId(),
			holding.getInstrument().getId(),
			holding.getInstrument().getSymbol(),
			holding.getInstrument().getName(),
			valuation.quantity(),
			holding.getReservedQuantity(),
			valuation.averagePrice(),
			valuation.currentPrice(),
			valuation.evaluationAmount(),
			valuation.unrealizedPnl(),
			valuation.returnRate(),
			valuation.priceStatus().name());
	}
}
