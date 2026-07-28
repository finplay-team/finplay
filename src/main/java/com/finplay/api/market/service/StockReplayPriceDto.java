// StockReplayService가 계산한 종목별 현재가·원본 거래일·시장상태를 다음 계층(StockPriceProvider)에 전달한다.
package com.finplay.api.market.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record StockReplayPriceDto(
	boolean sessionReady,
	StockMarketStatus marketStatus,
	LocalDate sourceTradingDate,
	BigDecimal price,
	LocalDateTime sourceTime) {

	public boolean isPriceAvailable() {
		return price != null;
	}
}
