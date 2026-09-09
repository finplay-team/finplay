package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.market.service.TutorialPriceCandleDto;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PracticeTutorialCandleResponse(
	LocalDate date,
	BigDecimal open,
	BigDecimal high,
	BigDecimal low,
	BigDecimal close,
	boolean current) {
	public static PracticeTutorialCandleResponse from(TutorialPriceCandleDto candle) {
		return new PracticeTutorialCandleResponse(
			candle.date(), candle.open(), candle.high(), candle.low(), candle.close(), candle.current());
	}
}
