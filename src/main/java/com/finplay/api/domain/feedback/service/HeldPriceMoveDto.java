package com.finplay.api.domain.feedback.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record HeldPriceMoveDto(
	LocalDateTime windowStart,
	LocalDateTime windowEnd,
	BigDecimal changeRate,
	int minutesAfterBuy,
	int minutesBeforeSell,
	List<NewsSourceDto> sources) {

	public HeldPriceMoveDto {
		sources = List.copyOf(sources);
	}
}
