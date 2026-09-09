package com.finplay.api.domain.education.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PracticeIntention(
	Long intentionId,
	Long userId,
	Long instrumentId,
	BigDecimal quantity,
	BigDecimal stopLoss,
	BigDecimal takeProfit,
	LocalDateTime createdAt) {

	public static PracticeIntention create(
		Long intentionId,
		Long userId,
		Long instrumentId,
		BigDecimal quantity,
		BigDecimal stopLoss,
		BigDecimal takeProfit,
		LocalDateTime createdAt) {
		return new PracticeIntention(
			intentionId, userId, instrumentId, quantity, stopLoss, takeProfit, createdAt);
	}
}
