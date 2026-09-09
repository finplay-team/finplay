package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.entity.Trade;
import java.math.BigDecimal;

public record PracticeRunTradeSummaryDto(
	BigDecimal buyQuantity,
	BigDecimal sellQuantity,
	BigDecimal remainingQuantity,
	Trade firstSellTrade,
	BigDecimal averageBuyPrice,
	BigDecimal averageSellPrice,
	Long realizedPnl,
	Long soldBuyBasis) {
}
