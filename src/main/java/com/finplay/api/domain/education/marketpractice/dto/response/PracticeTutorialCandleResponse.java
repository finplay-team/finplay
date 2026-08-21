// 튜토리얼 차트의 일별 OHLC와 현재 봉 여부를 반환하는 응답 DTO
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
