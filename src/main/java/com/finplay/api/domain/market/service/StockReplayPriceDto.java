package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.StockReplaySession;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record StockReplayPriceDto(
	boolean sessionReady,
	StockMarketStatus marketStatus,
	LocalDate sourceTradingDate,
	BigDecimal price,
	LocalDateTime sourceTime,
	StockReplaySession replaySession) {

	public StockReplayPriceDto(
		boolean sessionReady,
		StockMarketStatus marketStatus,
		LocalDate sourceTradingDate,
		BigDecimal price,
		LocalDateTime sourceTime) {
		this(sessionReady, marketStatus, sourceTradingDate, price, sourceTime, null);
	}

	public boolean isPriceAvailable() {
		return price != null;
	}
}
