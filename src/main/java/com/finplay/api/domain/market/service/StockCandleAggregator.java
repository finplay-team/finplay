package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StockCandleAggregator {

	private StockCandleAggregator() {}

	static List<StockCandleDto> aggregate(List<StockCandleDto> minuteCandles, CandleInterval interval) {
		if (!interval.isAggregated()) {
			throw new IllegalArgumentException("StockCandleAggregator는 1m을 집계하지 않습니다: " + interval);
		}
		if (minuteCandles.isEmpty()) {
			return List.of();
		}

		Map<LocalDate, BucketAccumulator> buckets = new LinkedHashMap<>();
		for (StockCandleDto candle : minuteCandles) {
			LocalDate bucketStart = resolveBucketStart(candle.tradingDate(), interval);
			buckets.computeIfAbsent(bucketStart, key -> new BucketAccumulator()).accumulate(candle);
		}

		List<StockCandleDto> result = new ArrayList<>();
		for (Map.Entry<LocalDate, BucketAccumulator> entry : buckets.entrySet()) {
			result.add(entry.getValue().toDto(entry.getKey()));
		}
		return result;
	}

	static LocalDate resolveBucketStart(LocalDate tradingDate, CandleInterval interval) {
		return switch (interval) {
			case ONE_DAY -> tradingDate;
			case ONE_WEEK -> tradingDate.with(DayOfWeek.MONDAY);
			case ONE_MONTH -> tradingDate.withDayOfMonth(1);
			case ONE_MINUTE -> throw new IllegalArgumentException("StockCandleAggregator는 1m을 집계하지 않습니다.");
		};
	}

	private static final class BucketAccumulator {
		private BigDecimal open;
		private BigDecimal high;
		private BigDecimal low;
		private BigDecimal close;
		private long volume;

		private void accumulate(StockCandleDto candle) {
			if (open == null) {
				open = candle.open();
				high = candle.high();
				low = candle.low();
			} else {
				high = high.max(candle.high());
				low = low.min(candle.low());
			}
			close = candle.close();
			volume += candle.volume();
		}

		private StockCandleDto toDto(LocalDate bucketStart) {
			return new StockCandleDto(bucketStart, LocalTime.MIDNIGHT, open, high, low, close, volume);
		}
	}
}
