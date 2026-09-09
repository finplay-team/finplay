package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class NewsItemTruncator {

	private static final Comparator<MarketNewsItem> NEWEST_FIRST = Comparator
		.comparing(MarketNewsItem::getPublishedAt)
		.thenComparing(MarketNewsItem::getId, Comparator.nullsFirst(Comparator.naturalOrder()))
		.reversed();

	private NewsItemTruncator() {}

	public static List<MarketNewsItem> truncateAndSort(List<MarketNewsItem> candidates, int limit) {
		List<MarketNewsItem> ordered = new ArrayList<>(candidates);
		ordered.sort(NEWEST_FIRST);
		if (ordered.size() <= limit) {
			return List.copyOf(ordered);
		}

		List<MarketNewsItem> selected = new ArrayList<>(pick(ordered, MarketNewsItemType.DISCLOSURE, limit));
		selected.addAll(pick(ordered, MarketNewsItemType.NEWS, limit - selected.size()));
		selected.sort(NEWEST_FIRST);
		return List.copyOf(selected);
	}

	private static List<MarketNewsItem> pick(List<MarketNewsItem> ordered, MarketNewsItemType type, int count) {
		return ordered.stream().filter(item -> item.getType() == type).limit(count).toList();
	}
}
