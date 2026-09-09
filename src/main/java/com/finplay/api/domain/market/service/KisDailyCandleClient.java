package com.finplay.api.domain.market.service;

import java.time.LocalDate;
import java.util.List;

public interface KisDailyCandleClient {

	List<RawDailyCandleDto> fetchDailyCandles(String symbol, LocalDate from, LocalDate to);
}
