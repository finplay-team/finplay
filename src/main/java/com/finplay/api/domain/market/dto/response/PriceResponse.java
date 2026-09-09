package com.finplay.api.domain.market.dto.response;

import com.finplay.api.domain.market.service.PriceQuoteDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PriceResponse(BigDecimal price, LocalDateTime sourceTime, String status, LocalDate sourceTradingDate) {

	public static PriceResponse from(PriceQuoteDto quote) {
		return new PriceResponse(quote.price(), quote.sourceTime(), quote.status().name(), quote.sourceTradingDate());
	}
}
