// 실제 KIS Open API 호출 없이 종목별 고정 일봉 리스트를 반환하는 테스트 전용 KisDailyCandleClient 구현 (ADR-0003)
package com.finplay.api.market.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeKisDailyCandleClient implements KisDailyCandleClient {

	private final Map<String, List<RawDailyCandleDto>> candlesBySymbol = new HashMap<>();

	// 테스트 전용: 이 종목의 fetchDailyCandles 호출 결과를 고정한다.
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
