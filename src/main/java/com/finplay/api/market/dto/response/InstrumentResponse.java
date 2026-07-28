// 종목의 공개 필드만 노출하는 응답 DTO
package com.finplay.api.market.dto.response;

import com.finplay.api.market.domain.Instrument;
import java.math.BigDecimal;

public record InstrumentResponse(
	Long instrumentId,
	String market,
	String symbol,
	String name,
	BigDecimal tickSize,
	Long minOrderAmount,
	Boolean tradable) {

	public static InstrumentResponse from(Instrument instrument) {
		return new InstrumentResponse(
			instrument.getId(),
			instrument.getMarket().name(),
			instrument.getSymbol(),
			instrument.getName(),
			instrument.getTickSize(),
			instrument.getMinOrderAmount(),
			instrument.isTradable());
	}
}
