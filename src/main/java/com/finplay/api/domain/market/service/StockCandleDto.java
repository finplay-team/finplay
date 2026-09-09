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
