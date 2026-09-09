package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.StockDailyCandle;
import java.util.List;

record DailyInstrumentOutcome(Instrument instrument, List<StockDailyCandle> candles, String failureReason) {
	DailyInstrumentOutcome {
		candles = List.copyOf(candles);
	}
}
