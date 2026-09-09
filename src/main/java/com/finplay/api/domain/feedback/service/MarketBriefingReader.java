package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.repository.MarketBriefingRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MarketBriefingReader {

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final MarketBriefingRepository marketBriefingRepository;

	private final BusinessDayCalendar businessDayCalendar;

	private final FeedbackNewsProperties properties;

	@Transactional(readOnly = true)
	public List<BriefingNewsItem> readStockBriefingItems(LocalDate originTradeDate) {
		return NewsItemTruncator
			.truncateAndSort(collectPreMarketItems(originTradeDate), properties.maxItemsPerBriefing())
			.stream()
			.map(BriefingNewsItem::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public SummaryTextLookupDto readStockBriefingText(LocalDate originTradeDate) {
		return marketBriefingRepository.findByMarketAndOriginTradeDate(Market.STOCK, originTradeDate)
			.map(briefing -> SummaryTextLookupDto.row(briefing.getSummary()))
			.orElseGet(SummaryTextLookupDto::missingRow);
	}

	@Transactional(readOnly = true)
	public List<BriefingNewsItem> readCryptoBriefingItems(LocalDateTime now) {
		return NewsItemTruncator
			.truncateAndSort(collectRollingItems(now), properties.maxItemsPerBriefing())
			.stream()
			.map(BriefingNewsItem::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public SummaryTextLookupDto readLatestCryptoBriefingText() {
		return marketBriefingRepository.findFirstByMarketOrderByGeneratedAtDescIdDesc(Market.CRYPTO)
			.map(briefing -> SummaryTextLookupDto.row(briefing.getSummary()))
			.orElseGet(SummaryTextLookupDto::missingRow);
	}

	@Transactional(readOnly = true)
	public List<MarketNewsItem> collectPreMarketItems(LocalDate originTradeDate) {
		LocalDate previousTradingDate = businessDayCalendar.previousBusinessDay(originTradeDate);
		List<MarketNewsItem> items = new ArrayList<>(marketNewsItemRepository.findMarketNewsPublishedBetween(
			Market.STOCK,
			LocalDateTime.of(previousTradingDate, MarketSessionTimes.MARKET_CLOSE_TIME),
			LocalDateTime.of(originTradeDate, MarketSessionTimes.MARKET_OPEN_TIME)));
		items.addAll(marketNewsItemRepository.findMarketDisclosuresReceivedOn(
			Market.STOCK,
			previousTradingDate.atStartOfDay(),
			previousTradingDate.plusDays(1).atStartOfDay()));
		return items;
	}

	@Transactional(readOnly = true)
	public List<MarketNewsItem> collectRollingItems(LocalDateTime now) {
		return marketNewsItemRepository.findMarketNewsPublishedBetween(
			Market.CRYPTO, now.minus(MarketSessionTimes.ROLLING_WINDOW), now);
	}
}
