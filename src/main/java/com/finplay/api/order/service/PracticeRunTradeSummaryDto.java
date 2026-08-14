// 현재 attempt/run의 불변 BUY·SELL 합계와 최초 SELL을 제공하는 order evidence DTO
package com.finplay.api.order.service;

import com.finplay.api.order.domain.Trade;
import java.math.BigDecimal;

public record PracticeRunTradeSummaryDto(
	BigDecimal buyQuantity,
	BigDecimal sellQuantity,
	BigDecimal remainingQuantity,
	Trade firstSellTrade) {
}
