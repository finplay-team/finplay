package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.store.FeedbackQueryCache;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentNewsSummaryService {

	private final MarketNewsItemRepository marketNewsItemRepository;

	private final InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	private final NarrativeService narrativeService;

	private final BusinessDayCalendar businessDayCalendar;

	private final FeedbackQueryCache feedbackQueryCache;

	private final FeedbackNewsProperties properties;

	private final Clock clock;

	public Optional<InstrumentNewsSummary> generateStockSummary(
		Instrument instrument, LocalDate originTradeDate, NewsSummaryScope scope) {
		if (instrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope(
			instrument.getId(), originTradeDate, scope)) {
			log.debug("이미 있는 요약이라 건너뛴다. 종목={} 거래일={} 범위={}",
				instrument.getId(), originTradeDate, scope);
			return Optional.empty();
		}

		List<MarketNewsItem> items = NewsItemTruncator.truncateAndSort(
			collectItems(instrument.getId(), originTradeDate, scope), properties.maxItemsPerSummary());
		if (items.isEmpty()) {
			log.debug("대상 기사가 없어 요약을 만들지 않는다. 종목={} 거래일={} 범위={}",
				instrument.getId(), originTradeDate, scope);
			return Optional.empty();
		}

		NarrativeResultDto narrative = narrativeService.resolveNewsSummaryNarrative(
			new NewsSummaryPromptDto(
				instrument.getName(),
				scope,
				originTradeDate,
				items.stream().map(InstrumentNewsSummaryService::toSource).toList()));
		return Optional.of(instrumentNewsSummaryRepository.save(InstrumentNewsSummary.create(
			instrument,
			originTradeDate,
			scope,
			narrative.narrative(),
			narrative.source(),
			LocalDateTime.now(clock))));
	}

	public Optional<InstrumentNewsSummary> refreshCryptoSummary(Instrument instrument) {
		LocalDateTime now = LocalDateTime.now(clock);
		Optional<InstrumentNewsSummary> latest = instrumentNewsSummaryRepository
			.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(
				instrument.getId(), NewsSummaryScope.ROLLING_24H);
		if (latest.isPresent() && !marketNewsItemRepository.existsByInstrumentIdAndCreatedAtAfter(
			instrument.getId(), latest.get().getGeneratedAt())) {
			log.debug("직전 생성 이후 수집된 기사가 없어 요약을 다시 만들지 않는다. 종목={} 직전생성={}",
				instrument.getId(), latest.get().getGeneratedAt());
			return Optional.empty();
		}

		List<MarketNewsItem> items = NewsItemTruncator.truncateAndSort(
			marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrument.getId(),
				MarketNewsItemType.NEWS,
				now.minus(MarketSessionTimes.ROLLING_WINDOW),
				now),
			properties.maxItemsPerSummary());
		if (items.isEmpty()) {
			log.debug("최근 24시간 기사가 없어 요약을 만들지 않는다. 종목={}", instrument.getId());
			return Optional.empty();
		}

		LocalDate batchDate = now.toLocalDate();
		NarrativeResultDto narrative = narrativeService.resolveNewsSummaryNarrative(
			new NewsSummaryPromptDto(
				instrument.getName(),
				NewsSummaryScope.ROLLING_24H,
				batchDate,
				items.stream().map(InstrumentNewsSummaryService::toSource).toList()));
		InstrumentNewsSummary saved = instrumentNewsSummaryRepository.save(
			instrumentNewsSummaryRepository
				.findByInstrumentIdAndOriginTradeDateAndScope(
					instrument.getId(), batchDate, NewsSummaryScope.ROLLING_24H)
				.map(row -> {
					row.refreshNarrative(narrative.narrative(), narrative.source(), now);
					return row;
				})
				.orElseGet(() -> InstrumentNewsSummary.create(
					instrument,
					batchDate,
					NewsSummaryScope.ROLLING_24H,
					narrative.narrative(),
					narrative.source(),
					now)));
		feedbackQueryCache.evictCryptoSummaryText(instrument.getId());
		return Optional.of(saved);
	}

	private List<MarketNewsItem> collectItems(
		Long instrumentId, LocalDate originTradeDate, NewsSummaryScope scope) {
		LocalDate previousTradingDate = businessDayCalendar.previousBusinessDay(originTradeDate);
		LocalDateTime newsFrom = LocalDateTime.of(previousTradingDate, MarketSessionTimes.MARKET_CLOSE_TIME);
		LocalDateTime newsTo = switch (scope) {
			case PRE_MARKET -> LocalDateTime.of(originTradeDate, MarketSessionTimes.MARKET_OPEN_TIME);
			case FULL -> LocalDateTime.of(originTradeDate, MarketSessionTimes.MARKET_CLOSE_TIME);
			case ROLLING_24H -> throw new IllegalArgumentException(
				"ROLLING_24H는 코인 요약의 범위라 주식 요약 경로에서 쓸 수 없습니다.");
		};

		List<MarketNewsItem> items = new ArrayList<>(
			marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrumentId, MarketNewsItemType.NEWS, newsFrom, newsTo));
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

	private static NewsSourceDto toSource(MarketNewsItem item) {
		return new NewsSourceDto(
			item.getTitle(),
			item.getPublisher(),
			item.getPublishedAt(),
			item.getType() == MarketNewsItemType.DISCLOSURE);
	}
}
