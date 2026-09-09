package com.finplay.api.domain.market.service;

import java.time.LocalDateTime;
import java.util.List;

public interface StockPriceProvider {

	StockMarketStatus getMarketStatus();

	StockReplayPriceDto getCurrentPrice(Long instrumentId);

	List<StockReplayPriceDto> getCurrentPrices(List<Long> instrumentIds);

	List<StockCandleDto> getCandles(Long instrumentId, CandleInterval interval, LocalDateTime from, LocalDateTime to);
}
