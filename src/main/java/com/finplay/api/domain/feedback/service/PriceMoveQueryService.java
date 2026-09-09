package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.PriceMoveListResponse;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.market.service.StockReplaySessionDto;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PriceMoveQueryService {

	private final InstrumentService instrumentService;

	private final StockReplayService stockReplayService;

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveSourceLoader priceMoveSourceLoader;

	private final FeedbackCryptoProperties cryptoProperties;

	private final Clock clock;

	@Transactional(readOnly = true)
	public PriceMoveListResponse getPriceMoves(Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		if (instrument.getMarket() == Market.CRYPTO) {
			return getCryptoPriceMoves(instrument);
		}

		StockReplaySessionDto session = stockReplayService.getCurrentReplaySession();
		if (!session.ready()) {
			return PriceMoveListResponse.notYet();
		}

		List<PriceMoveEvent> events = priceMoveEventRepository
			.findByInstrumentIdAndOriginTradeDateAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				instrumentId, session.sourceTradingDate(), LocalTime.now(clock));
		if (events.isEmpty()) {
			return PriceMoveListResponse.of(session.sourceTradingDate(), List.of());
		}

		Map<Long, List<NewsItem>> sourcesByEventId = priceMoveSourceLoader.findSources(events);
		List<PriceMoveItem> moves = events.stream()
			.map(event -> PriceMoveItem.ofStock(
				event, sourcesByEventId.getOrDefault(event.getId(), List.of())))
			.toList();
		return PriceMoveListResponse.of(session.sourceTradingDate(), moves);
	}

	private PriceMoveListResponse getCryptoPriceMoves(Instrument instrument) {
		LocalDateTime now = LocalDateTime.now(clock);
		List<PriceMoveEvent> events = priceMoveEventRepository
			.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
				instrument.getId(), Market.CRYPTO, now.minusHours(24), now);
		if (events.isEmpty()) {
			return PriceMoveListResponse.of(null, List.of());
		}

		Map<Long, List<NewsItem>> sourcesByEventId = priceMoveSourceLoader.findSources(events);
		List<PriceMoveItem> moves = events.stream()
			.map(event -> PriceMoveItem.ofCrypto(
				event,
				sourcesByEventId.getOrDefault(event.getId(), List.of()),
				cryptoProperties.rollingWindowMinutes()))
			.toList();
		return PriceMoveListResponse.of(null, moves);
	}

}
