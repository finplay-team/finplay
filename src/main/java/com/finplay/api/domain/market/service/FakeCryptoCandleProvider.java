package com.finplay.api.domain.market.service;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod & !crypto-real")
public class FakeCryptoCandleProvider implements CryptoCandleProvider {

	private final Map<CandleInterval, Map<String, List<CryptoCandleDto>>> candlesByIntervalAndSymbol = new EnumMap<>(
		CandleInterval.class);

	private volatile boolean failing;

	@Override
	public List<CryptoCandleDto> getCandles(
		String symbol, CandleInterval interval, LocalDateTime from, LocalDateTime to) {
		if (failing) {
			throw new BusinessException(ErrorCode.MARKET_DATA_PROVIDER_ERROR);
		}
		List<CryptoCandleDto> candles = candlesByIntervalAndSymbol
			.getOrDefault(interval, Map.of())
			.getOrDefault(symbol, List.of());
		return candles.stream()
			.filter(candle -> from == null || !candle.sourceTime().isBefore(from))
			.filter(candle -> to == null || !candle.sourceTime().isAfter(to))
			.toList();
	}

	public void setCandles(String symbol, List<CryptoCandleDto> candles) {
		setCandles(symbol, CandleInterval.ONE_MINUTE, candles);
	}

	public void setCandles(String symbol, CandleInterval interval, List<CryptoCandleDto> candles) {
		candlesByIntervalAndSymbol
			.computeIfAbsent(interval, key -> new HashMap<>())
			.put(symbol, new ArrayList<>(candles));
	}

	public void simulateFailure() {
		failing = true;
	}

	public void reset() {
		failing = false;
	}
}
