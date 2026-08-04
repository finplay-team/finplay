// 주문 체결 시 같은 관측에서 확정한 가격과 주식 재생세션을 주문 도메인에 전달하는 내부 DTO
package com.finplay.api.market.service;

import com.finplay.api.market.domain.StockReplaySession;

public record OrderExecutionPriceDto(PriceQuoteDto priceQuote, StockReplaySession stockReplaySession) {
}
