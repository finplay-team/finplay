package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.time.LocalTime;

public record RawMinuteCandleDto(
	LocalTime candleTime,
	BigDecimal open,
	BigDecimal high,
	BigDecimal low,
	BigDecimal close,
	Long volume) {
}
