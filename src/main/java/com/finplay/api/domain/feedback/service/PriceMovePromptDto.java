package com.finplay.api.domain.feedback.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record PriceMovePromptDto(
	String instrumentName,
	boolean openingGap,
	LocalTime windowStart,
	LocalTime windowEnd,
	int windowMinutes,
	BigDecimal changeRate,
	LocalDate referenceDate,
	List<NewsSourceDto> sources) {

	public PriceMovePromptDto {
		sources = List.copyOf(sources);
	}
}
