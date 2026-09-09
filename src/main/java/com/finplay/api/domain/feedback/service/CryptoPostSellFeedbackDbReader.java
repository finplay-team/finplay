package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Trade;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class CryptoPostSellFeedbackDbReader {

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveSourceLoader priceMoveSourceLoader;

	private final PriceMovePeerStatRepository priceMovePeerStatRepository;

	private final FeedbackCryptoProperties cryptoProperties;

	@Transactional(readOnly = true)
	List<HeldPriceMoveItem> findHeldPriceMoves(Trade trade, LocalDateTime buyAt, LocalDateTime sellAt) {
		List<PriceMoveEvent> events = priceMoveEventRepository
			.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
				trade.getInstrument().getId(),
				Market.CRYPTO,
				PostSellArithmetic.onMinuteBoundary(buyAt),
				PostSellArithmetic.onMinuteBoundary(sellAt));
		if (events.isEmpty()) {
			return List.of();
		}

		Map<Long, List<NewsItem>> sourcesByEventId = priceMoveSourceLoader.findSources(events);
		return events.stream()
			.map(event -> toHeldPriceMoveItem(
				event, buyAt, sellAt, sourcesByEventId.getOrDefault(event.getId(), List.of())))
			.toList();
	}

	private HeldPriceMoveItem toHeldPriceMoveItem(
		PriceMoveEvent event, LocalDateTime buyAt, LocalDateTime sellAt, List<NewsItem> sources) {
		LocalDateTime windowEnd = event.getOccurredAt();
		return new HeldPriceMoveItem(
			event.getId(),
			windowEnd.minusMinutes(cryptoProperties.rollingWindowMinutes()),
			windowEnd,
			event.getChangeRate(),
			PostSellArithmetic.minutesBetween(buyAt, windowEnd),
			PostSellArithmetic.minutesBetween(windowEnd, sellAt),
			event.getNarrative(),
			sources);
	}

	@Transactional(readOnly = true)
	PeerComparison buildPeerComparison(List<HeldPriceMoveItem> priceMoves) {
		if (priceMoves.isEmpty()) {
			return PostSellArithmetic.peerComparisonNoEvent();
		}

		HeldPriceMoveItem card = priceMoves.get(0);
		Integer yourMinutesToSell = card.minutesBeforeSell();
		return priceMovePeerStatRepository
			.findByPriceMoveEventIdAndServiceDate(card.id(), card.windowEnd().toLocalDate())
			.map(stat -> PostSellArithmetic.toPeerComparison(stat, card.id(), yourMinutesToSell))
			.orElseGet(PostSellArithmetic::peerComparisonNotYet);
	}

}
