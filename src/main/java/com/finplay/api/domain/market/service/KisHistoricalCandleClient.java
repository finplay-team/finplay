package com.finplay.api.domain.market.service;

import java.time.LocalDate;
import java.util.List;

public interface KisHistoricalCandleClient {

	List<RawMinuteCandleDto> fetchMinuteCandles(String symbol, LocalDate tradingDate);
}
