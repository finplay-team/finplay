package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.util.List;

public record TutorialPriceSeriesDto(
	List<TutorialPriceCandleDto> candles,
	BigDecimal canonicalPrice) {
	public TutorialPriceSeriesDto {
		candles = List.copyOf(candles);
	}
}
