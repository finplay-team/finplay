package com.finplay.api.domain.feedback.dto.response;

import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;

public record TradeShareSummaryResponse(
	String symbol,
	String name,
	Market market,
	BigDecimal buyPrice,
	BigDecimal sellPrice,
	BigDecimal quantity,
	Long realizedPnl,
	BigDecimal returnRate) {
}
