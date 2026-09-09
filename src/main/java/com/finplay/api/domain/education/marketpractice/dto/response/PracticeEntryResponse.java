package com.finplay.api.domain.education.marketpractice.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PracticeEntryResponse(
	int entrySequence,
	String exitPreset,
	BigDecimal stopLossRate,
	BigDecimal takeProfitRate,
	String buyOrderType,
	LocalDateTime buyAt,
	BigDecimal buyPrice,
	BigDecimal buyQuantity,
	BigDecimal stopLossPrice,
	BigDecimal takeProfitPrice,
	BigDecimal sellPrice,
	BigDecimal sellQuantity,
	LocalDateTime sellAt,
	String sellCause,
	Long realizedPnl,
	Long unrealizedPnlIfHeld,
	String scenarioScriptId) {
}
