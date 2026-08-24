// favorite -> intention -> buyTrade -> holding chain 해석 성공 결과를 담아 education 서비스 간 내부 전달하는 DTO
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
