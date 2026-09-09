package com.finplay.api.domain.market.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CryptoPriceUpdatedEvent(String symbol, BigDecimal price, LocalDateTime receivedAt,
	LocalDateTime observedAt) {
}
