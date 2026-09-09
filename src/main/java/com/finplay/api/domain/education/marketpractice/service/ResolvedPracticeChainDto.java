package com.finplay.api.domain.education.marketpractice.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ResolvedPracticeChainDto(
	Long favoriteId,
	LocalDateTime favoriteCreatedAt,
	Long intentionId,
	LocalDateTime intentionCreatedAt,
	BigDecimal intentionStopLoss,
	BigDecimal intentionTakeProfit,
	Long buyTradeId,
	LocalDateTime buyTradeExecutedAt,
	BigDecimal buyTradeEntryPrice,
	Long holdingId,
	Long sellTradeId,
	LocalDateTime sellTradeExecutedAt,
	boolean instrumentIsTutorialSample) {
}
