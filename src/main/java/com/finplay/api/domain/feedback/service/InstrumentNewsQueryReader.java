package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import com.finplay.api.domain.market.service.InstrumentService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class InstrumentNewsQueryReader {

	private final InstrumentService instrumentService;

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	private final BusinessDayCalendar businessDayCalendar;

	private final FeedbackNewsProperties properties;

	@Transactional(readOnly = true)
	public Market readMarket(Long instrumentId) {
		return instrumentService.getInstrumentEntity(instrumentId).getMarket();
	}

	@Transactional(readOnly = true)
	public List<NewsItem> readStockItems(
		Long instrumentId, LocalDate originTradeDate, NewsSummaryScope scope, LocalTime now) {
		return NewsItemTruncator
			.truncateAndSort(collectVisibleItems(instrumentId, originTradeDate, scope, now),
				properties.maxItemsPerNewsList())
			.stream()
			.map(NewsItem::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public SummaryTextLookupDto readStockSummary(
		Long instrumentId, LocalDate originTradeDate, NewsSummaryScope scope) {
		return instrumentNewsSummaryRepository
			.findByInstrumentIdAndOriginTradeDateAndScope(instrumentId, originTradeDate, scope)
			.map(summary -> SummaryTextLookupDto.row(summary.getSummary()))
			.orElseGet(SummaryTextLookupDto::missingRow);
	}

	@Transactional(readOnly = true)
	public List<NewsItem> readCryptoItems(Long instrumentId, LocalDateTime now) {
		return NewsItemTruncator
			.truncateAndSort(
				marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
					instrumentId,
					MarketNewsItemType.NEWS,
					now.minus(MarketSessionTimes.ROLLING_WINDOW),
					now),
				properties.maxItemsPerNewsList())
			.stream()
			.map(NewsItem::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public SummaryTextLookupDto readLatestCryptoSummary(Long instrumentId) {
		return instrumentNewsSummaryRepository
			.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(instrumentId, NewsSummaryScope.ROLLING_24H)
			.map(summary -> SummaryTextLookupDto.row(summary.getSummary()))
			.orElseGet(SummaryTextLookupDto::missingRow);
	}

	private List<MarketNewsItem> collectVisibleItems(
		Long instrumentId, LocalDate originTradeDate, NewsSummaryScope scope, LocalTime now) {
		LocalDate previousTradingDate = businessDayCalendar.previousBusinessDay(originTradeDate);
		LocalTime newsUpperBound = now.isBefore(MarketSessionTimes.MARKET_CLOSE_TIME)
			? now
			: MarketSessionTimes.MARKET_CLOSE_TIME;

		List<MarketNewsItem> items = new ArrayList<>(
			marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentId,
				MarketNewsItemType.NEWS,
				LocalDateTime.of(previousTradingDate, MarketSessionTimes.MARKET_CLOSE_TIME),
				LocalDateTime.of(originTradeDate, newsUpperBound)));
		items.addAll(disclosuresOn(instrumentId, previousTradingDate));
		if (scope == NewsSummaryScope.FULL) {
			items.addAll(disclosuresOn(instrumentId, originTradeDate));
		}
		return items;
	}

	private List<MarketNewsItem> disclosuresOn(Long instrumentId, LocalDate receivedDate) {
		return marketNewsItemRepository.findDisclosuresReceivedOn(
			instrumentId, receivedDate.atStartOfDay(), receivedDate.plusDays(1).atStartOfDay());
	}
}
