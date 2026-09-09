package com.finplay.api.domain.market.service;

import java.time.LocalDateTime;
import java.util.List;

public interface CryptoCandleProvider {

	List<CryptoCandleDto> getCandles(String symbol, CandleInterval interval, LocalDateTime from, LocalDateTime to);
}
