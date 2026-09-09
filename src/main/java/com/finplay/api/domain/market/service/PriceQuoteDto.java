package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PriceQuoteDto(BigDecimal price, LocalDateTime sourceTime, PriceStatus status,
	LocalDate sourceTradingDate) {
}
