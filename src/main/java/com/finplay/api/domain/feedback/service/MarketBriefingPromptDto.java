package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.market.entity.Market;
import java.time.LocalDate;
import java.util.List;

public record MarketBriefingPromptDto(
	Market market,
	LocalDate referenceDate,
	List<BriefingNewsItemDto> items) {

	public MarketBriefingPromptDto {
		items = List.copyOf(items);
	}
}
