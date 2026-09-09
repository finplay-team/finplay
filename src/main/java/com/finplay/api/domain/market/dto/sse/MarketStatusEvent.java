package com.finplay.api.domain.market.dto.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.market.service.StockMarketStatus;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MarketStatusEvent(
	Market market,
	String symbol,
	StockMarketStatus marketStatus,
	PriceStatus status,
	String reason,
	LocalDateTime emittedAt) {

}
