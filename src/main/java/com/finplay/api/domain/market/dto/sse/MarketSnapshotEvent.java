package com.finplay.api.domain.market.dto.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.market.service.StockMarketStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketSnapshotEvent(
	Market market,
	LocalDate sourceTradingDate,
	StockMarketStatus marketStatus,
	LocalDateTime emittedAt,
	List<InstrumentPriceSnapshot> prices) {

	public MarketSnapshotEvent {
		prices = List.copyOf(prices);
	}

	public record InstrumentPriceSnapshot(String symbol, BigDecimal price, LocalDateTime sourceTime,
		PriceStatus status) {

		public static InstrumentPriceSnapshot of(String symbol, BigDecimal price, LocalDateTime sourceTime,
			PriceStatus status) {
			return new InstrumentPriceSnapshot(symbol, price, sourceTime, status);
		}
	}
}
