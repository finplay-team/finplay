package com.finplay.api.domain.market.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeKisDailyCandleClient implements KisDailyCandleClient {

	private final Map<String, List<RawDailyCandleDto>> candlesBySymbol = new HashMap<>();

	public void setCandles(String symbol, List<RawDailyCandleDto> candles) {
		candlesBySymbol.put(symbol, candles);
	}

	@Override
	public List<RawDailyCandleDto> fetchDailyCandles(String symbol, LocalDate from, LocalDate to) {
		return candlesBySymbol.getOrDefault(symbol, List.of()).stream()
			.filter(candle -> !candle.tradingDate().isBefore(from) && !candle.tradingDate().isAfter(to))
			.toList();
	}
}
