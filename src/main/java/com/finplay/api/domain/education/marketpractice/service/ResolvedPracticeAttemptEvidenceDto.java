package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.entity.PracticeSellCause;
import com.finplay.api.domain.order.entity.Trade;
import java.math.BigDecimal;

public record ResolvedPracticeAttemptEvidenceDto(
	PracticeRiskSnapshot riskSnapshot,
	PracticeRiskSnapshot observationBaseline,
	Long holdingId,
	BigDecimal buyQuantity,
	BigDecimal sellQuantity,
	BigDecimal remainingQuantity,
	Trade sellTrade,
	BigDecimal averageBuyPrice,
	BigDecimal averageSellPrice,
	Long realizedPnl,
	Long soldBuyBasis,
	PracticeSellCause sellCause) {
}
