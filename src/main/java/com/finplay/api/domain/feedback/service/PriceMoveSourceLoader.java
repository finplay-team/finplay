package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class PriceMoveSourceLoader {

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	public Map<Long, List<NewsItem>> findSources(List<PriceMoveEvent> events) {
		List<Long> eventIds = events.stream().map(PriceMoveEvent::getId).toList();
		Map<Long, List<NewsItem>> sourcesByEventId = new LinkedHashMap<>();
		for (PriceMoveEventSource source : priceMoveEventSourceRepository
			.findAllByPriceMoveEventIdIn(eventIds)) {
			sourcesByEventId
				.computeIfAbsent(source.getPriceMoveEvent().getId(), id -> new ArrayList<>())
				.add(NewsItem.from(source.getMarketNewsItem()));
		}
		return sourcesByEventId;
	}
}
