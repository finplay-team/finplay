package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CryptoCandleDto(
	LocalDateTime sourceTime, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close, BigDecimal volume) {
}
