// KisHistoricalCandleClient가 반환하는 검증 전 원시 1분봉 값 — StockCandleDto·StockCandle 엔티티와는 별개의 이 계층 전용 DTO다.
package com.finplay.api.market.service;

import java.math.BigDecimal;
import java.time.LocalTime;

public record RawMinuteCandleDto(
	LocalTime candleTime,
	BigDecimal open,
	BigDecimal high,
	BigDecimal low,
	BigDecimal close,
	Long volume) {
}
