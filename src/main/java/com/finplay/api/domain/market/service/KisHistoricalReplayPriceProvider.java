package com.finplay.api.domain.market.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KisHistoricalReplayPriceProvider implements StockPriceProvider {

	private final StockReplayService stockReplayService;

	@Override
	public StockMarketStatus getMarketStatus() {
		return stockReplayService.getMarketStatus();
	}

	@Override
	public StockReplayPriceDto getCurrentPrice(Long instrumentId) {
		return stockReplayService.getCurrentPrice(instrumentId);
	}

	@Override
	public List<StockReplayPriceDto> getCurrentPrices(List<Long> instrumentIds) {
		return stockReplayService.getCurrentPrices(instrumentIds);
	}

	@Override
	public List<StockCandleDto> getCandles(
		Long instrumentId, CandleInterval interval, LocalDateTime from, LocalDateTime to) {
		if (interval.isAggregated()) {
			LocalDate fromDate = from != null ? from.toLocalDate() : null;
			LocalDate toDate = to != null ? to.toLocalDate() : null;
			return stockReplayService.getRevealedAggregatedCandles(instrumentId, interval, fromDate, toDate);
		}
		return stockReplayService.getRevealedCandles(instrumentId, from, to);
	}
}
