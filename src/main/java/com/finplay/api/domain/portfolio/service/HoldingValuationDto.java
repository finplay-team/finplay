package com.finplay.api.domain.portfolio.service;

import com.finplay.api.domain.market.service.PriceStatus;
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
