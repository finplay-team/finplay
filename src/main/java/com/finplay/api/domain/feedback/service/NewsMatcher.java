package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class NewsMatcher {

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final FeedbackNewsProperties properties;

	private final FeedbackCryptoProperties cryptoProperties;

	private final BusinessDayCalendar businessDayCalendar;

	@Transactional(readOnly = true)
	public List<MarketNewsItem> match(
		Long instrumentId, LocalDate originTradeDate, PriceMoveDetectionDto detection) {
		LocalDateTime eventAt = LocalDateTime.of(originTradeDate, detection.windowEnd());
		return switch (detection.eventType()) {
			case INTRADAY -> sortAndTruncate(
				matchIntraday(instrumentId, originTradeDate, detection.windowEnd()), eventAt);
			case OPENING_GAP -> truncateGap(matchOpeningGap(instrumentId, originTradeDate));
		};
	}

	@Transactional(readOnly = true)
	public List<MarketNewsItem> matchCrypto(Long instrumentId, LocalDateTime occurredAt) {
		List<MarketNewsItem> candidates = marketNewsItemRepository
			.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentId,
				MarketNewsItemType.NEWS,
				occurredAt.minusMinutes(cryptoProperties.matchBeforeMinutes()),
				occurredAt);
		return sortAndTruncate(candidates, occurredAt);
	}

	private List<MarketNewsItem> matchIntraday(
		Long instrumentId, LocalDate originTradeDate, LocalTime windowEnd) {
		LocalDateTime eventAt = LocalDateTime.of(originTradeDate, windowEnd);
		return marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			instrumentId,
			MarketNewsItemType.NEWS,
			eventAt.minusMinutes(properties.matchBeforeMinutes()),
			eventAt.plusMinutes(properties.matchAfterMinutes()));
	}

	private List<MarketNewsItem> matchOpeningGap(Long instrumentId, LocalDate originTradeDate) {
		LocalDate previousTradingDate = businessDayCalendar.previousBusinessDay(originTradeDate);
		List<MarketNewsItem> candidates = new ArrayList<>(
			marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentId,
				MarketNewsItemType.NEWS,
				LocalDateTime.of(previousTradingDate, MarketSessionTimes.MARKET_CLOSE_TIME),
				LocalDateTime.of(originTradeDate, MarketSessionTimes.MARKET_OPEN_TIME)));
		candidates.addAll(marketNewsItemRepository.findDisclosuresReceivedOn(
			instrumentId,
			previousTradingDate.atStartOfDay(),
			previousTradingDate.plusDays(1).atStartOfDay()));
		return candidates;
	}

	private List<MarketNewsItem> truncateGap(List<MarketNewsItem> candidates) {
		return NewsItemTruncator.truncateAndSort(candidates, properties.maxSourcesPerCard());
	}

	private List<MarketNewsItem> sortAndTruncate(List<MarketNewsItem> candidates, LocalDateTime eventAt) {
		List<MarketNewsItem> sorted = new ArrayList<>(candidates);
		sorted.sort(Comparator
			.comparing((MarketNewsItem item) -> Duration.between(eventAt, item.getPublishedAt()).abs())
			.thenComparing(MarketNewsItem::getPublishedAt, Comparator.reverseOrder())
			.thenComparing(MarketNewsItem::getUrl));
		int limit = properties.maxSourcesPerCard();
		return sorted.size() <= limit ? List.copyOf(sorted) : List.copyOf(sorted.subList(0, limit));
	}
}
