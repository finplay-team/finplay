// KisDailyCandleClient가 반환하는 검증된 원시 일봉 값 — StockDailyCandle 엔티티와는 별개의 이 계층 전용 DTO다.
package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RawDailyCandleDto(
	LocalDate tradingDate,
	BigDecimal open,
	BigDecimal high,
	BigDecimal low,
	BigDecimal close,
	Long volume) {
}
