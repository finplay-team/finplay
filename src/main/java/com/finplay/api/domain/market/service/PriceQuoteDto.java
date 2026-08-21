// PriceQueryService가 반환하는 "유효한 최신 가격" 계약 — 가격·캔들 API·SSE·모의 주문 체결·평가손익이 공통으로 재사용한다.
package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record PriceQuoteDto(BigDecimal price, LocalDateTime sourceTime, PriceStatus status,
	LocalDate sourceTradingDate) {
}
