package com.finplay.api.domain.feedback.dto.response;

import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import java.time.LocalDateTime;

public record BriefingNewsItem(
	Long instrumentId,
	String symbol,
	String name,
	MarketNewsItemType type,
	String title,
	String publisher,
	String url,
	LocalDateTime publishedAt) {

	public static BriefingNewsItem from(MarketNewsItem item) {
		return new BriefingNewsItem(
			item.getInstrument().getId(),
			item.getInstrument().getSymbol(),
			item.getInstrument().getName(),
			item.getType(),
			item.getTitle(),
			item.getPublisher(),
			item.getUrl(),
			item.getPublishedAt());
	}
}
