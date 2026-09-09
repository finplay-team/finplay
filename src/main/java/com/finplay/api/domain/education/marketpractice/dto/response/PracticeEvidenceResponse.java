package com.finplay.api.domain.education.marketpractice.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PracticeEvidenceResponse(
	Long favoriteId,
	LocalDateTime favoriteCreatedAt,
	Long intentionId,
	LocalDateTime intentionCreatedAt,
	Long buyTradeId,
	LocalDateTime buyTradeExecutedAt,
	Long holdingId,
	BigDecimal referenceStopLossPrice,
	BigDecimal referenceTakeProfitPrice,
	Long observationId,
	LocalDateTime observationObservedAt,
	String evidenceType,
	Long reflectionId,
	LocalDateTime reflectionCreatedAt,
	Long sellTradeId,
	LocalDateTime sellTradeExecutedAt,
	LocalDateTime saleDeadlineAt,
	BigDecimal buyQuantity,
	BigDecimal sellQuantity,
	BigDecimal remainingQuantity,
	PracticeTradeResultResponse tradeResult) {

	public static PracticeEvidenceResponse empty() {
		return new PracticeEvidenceResponse(
			null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null, null);
	}

	public static PracticeEvidenceResponse favoriteOnly(Long favoriteId, LocalDateTime favoriteCreatedAt) {
		return new PracticeEvidenceResponse(
			favoriteId, favoriteCreatedAt, null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null);
	}
}
