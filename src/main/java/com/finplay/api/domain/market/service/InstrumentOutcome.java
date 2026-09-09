package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.StockCandle;
import java.util.List;

record InstrumentOutcome(Instrument instrument, List<StockCandle> candles, String failureReason) {
	InstrumentOutcome {
		candles = List.copyOf(candles);
	}
}
