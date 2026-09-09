package com.finplay.api.domain.market.store;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CryptoPriceDto(String symbol, BigDecimal price, LocalDateTime receivedAt, LocalDateTime observedAt) {

	public CryptoPriceDto(String symbol, BigDecimal price, LocalDateTime receivedAt) {
		this(symbol, price, receivedAt, receivedAt);
	}
}
