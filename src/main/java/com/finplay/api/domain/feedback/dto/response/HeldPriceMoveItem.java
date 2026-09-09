package com.finplay.api.domain.feedback.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record HeldPriceMoveItem(
	Long id,
	LocalDateTime windowStart,
	LocalDateTime windowEnd,
	BigDecimal changeRate,
	int minutesAfterBuy,
	int minutesBeforeSell,
	String narrative,
	List<NewsItem> sources) {

	public HeldPriceMoveItem {
		sources = List.copyOf(sources);
	}
}
