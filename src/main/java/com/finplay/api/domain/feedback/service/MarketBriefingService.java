package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.domain.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.repository.MarketBriefingRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.store.FeedbackQueryCache;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.market.service.StockReplaySessionDto;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketBriefingService {

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final MarketBriefingRepository marketBriefingRepository;

	private final StockReplayService stockReplayService;

	private final NarrativeService narrativeService;

	private final MarketBriefingReader marketBriefingReader;

	private final FeedbackQueryCache feedbackQueryCache;

	private final FeedbackNewsProperties properties;

	private final Clock clock;

	public Optional<MarketBriefing> generateStockBriefing(LocalDate originTradeDate) {
		if (marketBriefingRepository.existsByMarketAndOriginTradeDate(Market.STOCK, originTradeDate)) {
			log.debug("이미 있는 브리핑이라 건너뛴다. 거래일={}", originTradeDate);
			return Optional.empty();
		}

		List<MarketNewsItem> items = NewsItemTruncator.truncateAndSort(
			marketBriefingReader.collectPreMarketItems(originTradeDate), properties.maxItemsPerSummary());
		if (items.isEmpty()) {
			log.debug("전장 구간 기사가 없어 브리핑을 만들지 않는다. 거래일={}", originTradeDate);
			return Optional.empty();
		}

		NarrativeResultDto narrative = narrativeService.resolveMarketBriefingNarrative(
			new MarketBriefingPromptDto(
				Market.STOCK, originTradeDate, items.stream().map(MarketBriefingService::toPromptItem).toList()));
		return Optional.of(marketBriefingRepository.save(MarketBriefing.create(
			Market.STOCK,
			originTradeDate,
			narrative.narrative(),
			narrative.source(),
			LocalDateTime.now(clock))));
	}

	public Optional<MarketBriefing> refreshCryptoBriefing() {
		LocalDateTime now = LocalDateTime.now(clock);
		Optional<MarketBriefing> latest = marketBriefingRepository
			.findFirstByMarketOrderByGeneratedAtDescIdDesc(Market.CRYPTO);
		if (latest.isPresent()
			&& !marketNewsItemRepository.existsCollectedAfter(Market.CRYPTO, latest.get().getGeneratedAt())) {
			log.debug("직전 생성 이후 수집된 코인 기사가 없어 브리핑을 다시 만들지 않는다. 직전생성={}",
				latest.get().getGeneratedAt());
			return Optional.empty();
		}

		List<MarketNewsItem> items = NewsItemTruncator.truncateAndSort(
			marketBriefingReader.collectRollingItems(now), properties.maxItemsPerSummary());
		if (items.isEmpty()) {
			log.debug("최근 24시간 코인 기사가 없어 브리핑을 만들지 않는다.");
			return Optional.empty();
		}

		LocalDate batchDate = now.toLocalDate();
		NarrativeResultDto narrative = narrativeService.resolveMarketBriefingNarrative(
			new MarketBriefingPromptDto(
				Market.CRYPTO, batchDate, items.stream().map(MarketBriefingService::toPromptItem).toList()));
		MarketBriefing saved = marketBriefingRepository.save(
			marketBriefingRepository.findByMarketAndOriginTradeDate(Market.CRYPTO, batchDate)
				.map(row -> {
					row.refreshNarrative(narrative.narrative(), narrative.source(), now);
					return row;
				})
				.orElseGet(() -> MarketBriefing.create(
					Market.CRYPTO, batchDate, narrative.narrative(), narrative.source(), now)));
		feedbackQueryCache.evictCryptoBriefingText();
		return Optional.of(saved);
	}

	public MarketBriefingResponse getBriefing(Market market) {
		if (market == Market.CRYPTO) {
			return getCryptoBriefing();
		}

		StockReplaySessionDto session = stockReplayService.getCurrentReplaySession();
		if (!session.ready()) {
			return MarketBriefingResponse.withoutItems(market, null, FeedbackContentStatus.EMPTY);
		}

		LocalDate originTradeDate = session.sourceTradingDate();
		if (LocalTime.now(clock).isBefore(MarketSessionTimes.MARKET_OPEN_TIME)) {
			return MarketBriefingResponse.withoutItems(
				market, originTradeDate, FeedbackContentStatus.NOT_YET);
		}

		List<BriefingNewsItem> items = feedbackQueryCache.getOrLoadStockBriefingItems(
			originTradeDate, () -> marketBriefingReader.readStockBriefingItems(originTradeDate));

		if (items.isEmpty()) {
			return MarketBriefingResponse.withoutItems(
				market, originTradeDate, FeedbackContentStatus.EMPTY);
		}

		AtomicBoolean briefingRowFound = new AtomicBoolean();
		Optional<String> text = feedbackQueryCache.getOrLoadStockBriefingText(originTradeDate, () -> {
			SummaryTextLookupDto lookup = marketBriefingReader.readStockBriefingText(originTradeDate);
			briefingRowFound.set(lookup.rowExists());
			return lookup.readyText();
		});
		if (text.isPresent()) {
			return MarketBriefingResponse.of(
				market, originTradeDate, FeedbackContentStatus.READY, text.get(), items);
		}

		return MarketBriefingResponse.of(
			market,
			originTradeDate,
			briefingRowFound.get() ? FeedbackContentStatus.UNAVAILABLE : FeedbackContentStatus.EMPTY,
			null,
			items);
	}

	private MarketBriefingResponse getCryptoBriefing() {
		List<BriefingNewsItem> items = marketBriefingReader.readCryptoBriefingItems(LocalDateTime.now(clock));
		if (items.isEmpty()) {
			return MarketBriefingResponse.withoutItems(Market.CRYPTO, null, FeedbackContentStatus.EMPTY);
		}

		AtomicBoolean briefingRowFound = new AtomicBoolean();
		Optional<String> text = feedbackQueryCache.getOrLoadCryptoBriefingText(() -> {
			SummaryTextLookupDto lookup = marketBriefingReader.readLatestCryptoBriefingText();
			briefingRowFound.set(lookup.rowExists());
			return lookup.readyText();
		});
		if (text.isPresent()) {
			return MarketBriefingResponse.of(
				Market.CRYPTO, null, FeedbackContentStatus.READY, text.get(), items);
		}

		return MarketBriefingResponse.of(
			Market.CRYPTO,
			null,
			briefingRowFound.get() ? FeedbackContentStatus.UNAVAILABLE : FeedbackContentStatus.EMPTY,
			null,
			items);
	}

	private static BriefingNewsItemDto toPromptItem(MarketNewsItem item) {
		return new BriefingNewsItemDto(
			item.getInstrument().getName(),
			new NewsSourceDto(
				item.getTitle(),
				item.getPublisher(),
				item.getPublishedAt(),
				item.getType() == MarketNewsItemType.DISCLOSURE));
	}
}
