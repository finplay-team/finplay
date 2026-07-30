// CryptoCandleProvider가 반환하는 코인 1분봉 데이터 — sourceTradingDate 개념이 없고, volume은 소수 수량이라 BigDecimal이다.
package com.finplay.api.market.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CryptoCandleDto(
	LocalDateTime sourceTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {
}
