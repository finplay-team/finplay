// StockPriceProvider가 반환하는 1분봉 캔들 데이터 — StockCandle 엔티티를 서비스 경계 밖으로 노출하지 않기 위한 DTO
package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.StockCandle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record StockCandleDto(
	LocalDate tradingDate,
	LocalTime candleTime,
	BigDecimal open,
	BigDecimal high,
	BigDecimal low,
	BigDecimal close,
	long volume) {

	public static StockCandleDto from(StockCandle candle) {
		return new StockCandleDto(
			candle.getTradingDate(),
			candle.getCandleTime(),
			candle.getOpen(),
			candle.getHigh(),
			candle.getLow(),
			candle.getClose(),
			candle.getVolume());
	}
}
