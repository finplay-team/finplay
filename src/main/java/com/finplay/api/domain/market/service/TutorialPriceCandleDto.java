package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TutorialPriceCandleDto(
	LocalDate date,
	BigDecimal open,
	BigDecimal high,
	BigDecimal low,
	BigDecimal close,
	boolean current) {
}
