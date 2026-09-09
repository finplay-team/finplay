package com.finplay.api.domain.feedback.dto.response;

import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import java.time.LocalDateTime;

public record NewsItem(
	MarketNewsItemType type,
	String title,
	String publisher,
	String url,
	LocalDateTime publishedAt) {

	public static NewsItem from(MarketNewsItem item) {
		return new NewsItem(
			item.getType(), item.getTitle(), item.getPublisher(), item.getUrl(), item.getPublishedAt());
	}
}
