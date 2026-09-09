package com.finplay.api.domain.market.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeKisHistoricalCandleClient implements KisHistoricalCandleClient {

	private final Map<String, List<RawMinuteCandleDto>> candlesBySymbol = new HashMap<>();

	public void setCandles(String symbol, List<RawMinuteCandleDto> candles) {
		candlesBySymbol.put(symbol, candles);
	}

	@Override
	public List<RawMinuteCandleDto> fetchMinuteCandles(String symbol, LocalDate tradingDate) {
		return candlesBySymbol.getOrDefault(symbol, List.of());
	}
}
