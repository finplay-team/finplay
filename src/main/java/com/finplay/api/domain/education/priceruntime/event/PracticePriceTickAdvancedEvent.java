package com.finplay.api.domain.education.priceruntime.event;

import java.math.BigDecimal;

public record PracticePriceTickAdvancedEvent(
	Long sessionId,
	Long userId,
	Long instrumentId,
	int tick,
	BigDecimal price,
	boolean lastTick) {
}
