// 시장별 보유 종목 목록 조회 결과 한 건을 노출하는 응답 DTO
package com.finplay.api.portfolio.dto.response;

import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.HoldingValuationDto;
import java.math.BigDecimal;

public record HoldingListItemResponse(
	Long instrumentId,
	String symbol,
	String name,
	BigDecimal quantity,
	BigDecimal averagePrice,
	BigDecimal currentPrice,
	Long evaluationAmount,
	Long unrealizedPnl,
	BigDecimal returnRate,
	String priceStatus) {

	public static HoldingListItemResponse from(Holding holding, HoldingValuationDto valuation) {
		return new HoldingListItemResponse(
			holding.getInstrument().getId(),
			holding.getInstrument().getSymbol(),
			holding.getInstrument().getName(),
			valuation.quantity(),
			valuation.averagePrice(),
			valuation.currentPrice(),
			valuation.evaluationAmount(),
			valuation.unrealizedPnl(),
			valuation.returnRate(),
			valuation.priceStatus().name());
	}
}
