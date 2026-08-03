// 주식·코인 종목의 캔들(1m·1d·1w·1M) 하나를 표현하는 공통 응답 DTO — volume은 코인의 소수 수량을 표현하기 위해 BigDecimal이다.
package com.finplay.api.market.dto.response;

import com.finplay.api.market.service.CryptoCandleDto;
import com.finplay.api.market.service.StockCandleDto;
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
			// 주식 volume은 정수 거래량(long)이다 — BigDecimal.valueOf는 scale 0으로 변환해 기존 응답 값 표현("12345")을 바꾸지 않는다.
			BigDecimal.valueOf(candle.volume()));
	}

	public static CandleResponse from(CryptoCandleDto candle) {
		return new CandleResponse(
			candle.sourceTime(), candle.open(), candle.high(), candle.low(), candle.close(), candle.volume());
	}
}
