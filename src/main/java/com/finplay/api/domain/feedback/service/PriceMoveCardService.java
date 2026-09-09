package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.market.entity.Instrument;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceMoveCardService {

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveCardWriter priceMoveCardWriter;

	private final NewsMatcher newsMatcher;

	private final NarrativeService narrativeService;

	private final Clock clock;

	public Optional<PriceMoveEvent> confirmStockCard(
		Instrument instrument, LocalDate originTradeDate, PriceMoveDetectionDto detection) {
		if (priceMoveEventRepository.existsByInstrumentIdAndOriginTradeDateAndEventTypeAndWindowStart(
			instrument.getId(), originTradeDate, detection.eventType(), detection.windowStart())) {
			log.debug("이미 있는 카드라 건너뛴다. 종목={} 거래일={} 종류={} 구간시작={}",
				instrument.getId(), originTradeDate, detection.eventType(), detection.windowStart());
			return Optional.empty();
		}

		List<MarketNewsItem> sources = newsMatcher.match(instrument.getId(), originTradeDate, detection);
		if (sources.isEmpty()) {
			log.debug("근거 기사가 없어 카드를 만들지 않는다. 종목={} 거래일={} 종류={}",
				instrument.getId(), originTradeDate, detection.eventType());
			return Optional.empty();
		}

		NarrativeResultDto narrative = narrativeService.resolvePriceMoveNarrative(
			toPrompt(instrument, originTradeDate, detection, sources));
		PriceMoveEvent card = PriceMoveEvent.createStock(
			instrument,
			detection.eventType(),
			originTradeDate,
			detection.windowStart(),
			detection.windowEnd(),
			detection.changeRate(),
			detection.detectionScore(),
			narrative.narrative(),
			narrative.source(),
			resolveRevealTime(detection, originTradeDate, sources),
			LocalDateTime.now(clock));
		return Optional.of(priceMoveCardWriter.persist(card, sources));
	}

	private static LocalTime resolveRevealTime(
		PriceMoveDetectionDto detection, LocalDate originTradeDate, List<MarketNewsItem> sources) {
		LocalTime latestSourceRevealTime = clamp(latestPublishedAt(sources), originTradeDate);
		if (detection.eventType() == PriceMoveEventType.OPENING_GAP) {
			return latestSourceRevealTime;
		}
		LocalTime afterWindow = detection.windowEnd().plusMinutes(1);
		return afterWindow.isAfter(latestSourceRevealTime) ? afterWindow : latestSourceRevealTime;
	}

	private static LocalTime clamp(LocalDateTime publishedAt, LocalDate originTradeDate) {
		return publishedAt.isBefore(
			LocalDateTime.of(originTradeDate, MarketSessionTimes.MARKET_OPEN_TIME))
				? MarketSessionTimes.MARKET_OPEN_TIME
				: publishedAt.toLocalTime();
	}

	private static LocalDateTime latestPublishedAt(List<MarketNewsItem> sources) {
		return sources.stream()
			.map(MarketNewsItem::getPublishedAt)
			.max(Comparator.naturalOrder())
			.orElseThrow(() -> new IllegalStateException("근거가 없는 카드는 만들지 않습니다."));
	}

	private static PriceMovePromptDto toPrompt(
		Instrument instrument,
		LocalDate originTradeDate,
		PriceMoveDetectionDto detection,
		List<MarketNewsItem> sources) {
		boolean openingGap = detection.eventType() == PriceMoveEventType.OPENING_GAP;
		return new PriceMovePromptDto(
			instrument.getName(),
			openingGap,
			detection.windowStart(),
			detection.windowEnd(),
			openingGap ? 0 : (int)Duration.between(detection.windowStart(), detection.windowEnd()).toMinutes(),
			detection.changeRate(),
			originTradeDate,
			sources.stream().map(PriceMoveCardService::toSource).toList());
	}

	private static NewsSourceDto toSource(MarketNewsItem item) {
		return new NewsSourceDto(
			item.getTitle(),
			item.getPublisher(),
			item.getPublishedAt(),
			item.getType() == MarketNewsItemType.DISCLOSURE);
	}
}
