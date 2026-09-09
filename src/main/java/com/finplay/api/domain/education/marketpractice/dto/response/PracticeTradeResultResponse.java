package com.finplay.api.domain.education.marketpractice.dto.response;

import java.math.BigDecimal;

public record PracticeTradeResultResponse(
	BigDecimal buyPrice,
	BigDecimal sellPrice,
	Long realizedPnl,
	BigDecimal returnRate,
	String sellVerdict,
	String sellCause) {
}
