// 보유 종목 1건의 원가·평가금액·미실현손익·수익률 계산 결과를 전달하는 내부 DTO
package com.finplay.api.portfolio.service;

import com.finplay.api.market.service.PriceStatus;
import java.math.BigDecimal;

public record HoldingValuationDto(
	BigDecimal quantity,
	BigDecimal averagePrice,
	long costBasis,
	PriceStatus priceStatus,
	BigDecimal currentPrice,
	Long evaluationAmount,
	Long unrealizedPnl,
	BigDecimal returnRate) {
}
