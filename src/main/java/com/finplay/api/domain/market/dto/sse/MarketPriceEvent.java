package com.finplay.api.domain.market.dto.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.StockMarketStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketPriceEvent(
	Market market,
	String symbol,
	BigDecimal price,
	LocalDateTime sourceTime,
	LocalDateTime emittedAt,
	LocalDate sourceTradingDate,
	StockMarketStatus marketStatus) {

}
