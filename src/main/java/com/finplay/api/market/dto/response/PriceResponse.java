// 종목의 유효한 최신 가격만 노출하는 응답 DTO
package com.finplay.api.market.dto.response;

import com.finplay.api.market.service.PriceQuoteDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PriceResponse(BigDecimal price, LocalDateTime sourceTime, String status, LocalDate sourceTradingDate) {

	public static PriceResponse from(PriceQuoteDto quote) {
		return new PriceResponse(quote.price(), quote.sourceTime(), quote.status().name(), quote.sourceTradingDate());
	}
}
