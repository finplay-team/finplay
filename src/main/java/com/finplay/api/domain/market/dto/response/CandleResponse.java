package com.finplay.api.domain.market.dto.response;

import com.finplay.api.domain.market.service.CryptoCandleDto;
import com.finplay.api.domain.market.service.StockCandleDto;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CandleResponse(LocalDateTime sourceTime, BigDecimal open, BigDecimal high, BigDecimal low,
	BigDecimal close, BigDecimal volume) {

	public static CandleResponse from(StockCandleDto candle) {
		return new CandleResponse(
			LocalDateTime.of(candle.tradingDate(), candle.candleTime()),
			candle.open(),
			candle.high(),
			candle.low(),
			candle.close(),
			BigDecimal.valueOf(candle.volume()));
	}

	public static CandleResponse from(CryptoCandleDto candle) {
		return new CandleResponse(
			candle.sourceTime(), candle.open(), candle.high(), candle.low(), candle.close(), candle.volume());
	}
}
