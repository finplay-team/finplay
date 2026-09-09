package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RawDailyCandleDto(
	LocalDate tradingDate,
	BigDecimal open,
	BigDecimal high,
	BigDecimal low,
	BigDecimal close,
	Long volume) {
}
