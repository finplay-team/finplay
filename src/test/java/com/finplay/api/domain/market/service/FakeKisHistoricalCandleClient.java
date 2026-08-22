// 실제 KIS Open API 호출 없이 종목별 고정 분봉 리스트를 반환하는 테스트 전용 KisHistoricalCandleClient 구현 (ADR-0003)
package com.finplay.api.domain.market.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FakeKisHistoricalCandleClient implements KisHistoricalCandleClient {

	private final Map<String, List<RawMinuteCandleDto>> candlesBySymbol = new HashMap<>();

	// 테스트 전용: 이 종목의 fetchMinuteCandles 호출 결과를 고정한다.
	public void setCandles(String symbol, List<RawMinuteCandleDto> candles) {
		candlesBySymbol.put(symbol, candles);
	}

	@Override
	public List<RawMinuteCandleDto> fetchMinuteCandles(String symbol, LocalDate tradingDate) {
		return candlesBySymbol.getOrDefault(symbol, List.of());
	}
}
