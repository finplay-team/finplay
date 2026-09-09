package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.StockReplaySession;

public record OrderExecutionPriceDto(PriceQuoteDto priceQuote, StockReplaySession stockReplaySession) {
}
