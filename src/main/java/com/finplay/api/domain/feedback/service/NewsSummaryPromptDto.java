package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import java.time.LocalDate;
import java.util.List;

public record NewsSummaryPromptDto(
	String instrumentName,
	NewsSummaryScope scope,
	LocalDate referenceDate,
	List<NewsSourceDto> items) {

	public NewsSummaryPromptDto {
		items = List.copyOf(items);
	}
}
