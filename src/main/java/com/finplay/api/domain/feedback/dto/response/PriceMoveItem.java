package com.finplay.api.domain.feedback.dto.response;

import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record PriceMoveItem(
	Long id,
	PriceMoveEventType eventType,
	LocalDateTime windowStart,
	LocalDateTime windowEnd,
	BigDecimal changeRate,
	String narrative,
	List<NewsItem> sources) {

	public PriceMoveItem {
		sources = List.copyOf(sources);
	}

	public static PriceMoveItem ofStock(PriceMoveEvent event, List<NewsItem> sources) {
		LocalDate originTradeDate = event.getOriginTradeDate();
		return new PriceMoveItem(
			event.getId(),
			event.getEventType(),
			atOriginTradeDate(originTradeDate, event.getWindowStart()),
			atOriginTradeDate(originTradeDate, event.getWindowEnd()),
			event.getChangeRate(),
			event.getNarrative(),
			sources);
	}

	public static PriceMoveItem ofCrypto(
		PriceMoveEvent event, List<NewsItem> sources, int rollingWindowMinutes) {
		LocalDateTime windowEnd = event.getOccurredAt();
		return new PriceMoveItem(
			event.getId(),
			event.getEventType(),
			windowEnd.minusMinutes(rollingWindowMinutes),
			windowEnd,
			event.getChangeRate(),
			event.getNarrative(),
			sources);
	}

	private static LocalDateTime atOriginTradeDate(LocalDate originTradeDate, LocalTime time) {
		return time == null ? null : LocalDateTime.of(originTradeDate, time);
	}
}
