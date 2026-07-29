// 주식 종목의 공개된 1분봉 하나를 표현하는 응답 DTO
package com.finplay.api.market.dto.response;

import com.finplay.api.market.service.StockCandleDto;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CandleResponse(LocalDateTime sourceTime, BigDecimal open, BigDecimal high, BigDecimal low,
	BigDecimal close, long volume) {

	public static CandleResponse from(StockCandleDto candle) {
		return new CandleResponse(
			LocalDateTime.of(candle.tradingDate(), candle.candleTime()),
			candle.open(),
			candle.high(),
			candle.low(),
			candle.close(),
			candle.volume());
	}
}
