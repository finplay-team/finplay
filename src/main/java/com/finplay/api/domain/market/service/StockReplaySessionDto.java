package com.finplay.api.domain.market.service;

import java.time.LocalDate;

public record StockReplaySessionDto(boolean ready, LocalDate sourceTradingDate) {
}
