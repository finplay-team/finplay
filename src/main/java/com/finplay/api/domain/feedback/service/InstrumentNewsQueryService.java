package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
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
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InstrumentNewsQueryService {

	private final InstrumentNewsQueryReader instrumentNewsQueryReader;

	private final StockReplayService stockReplayService;

	private final FeedbackQueryCache feedbackQueryCache;

	private final Clock clock;

	public InstrumentNewsResponse getInstrumentNews(Long instrumentId) {
		if (instrumentNewsQueryReader.readMarket(instrumentId) == Market.CRYPTO) {
			return getCryptoNews(instrumentId);
		}

		StockReplaySessionDto session = stockReplayService.getCurrentReplaySession();
		if (!session.ready()) {
			return InstrumentNewsResponse.notYet(null);
		}

		LocalDate originTradeDate = session.sourceTradingDate();
		LocalTime now = LocalTime.now(clock);
		if (now.isBefore(MarketSessionTimes.MARKET_OPEN_TIME)) {
			return InstrumentNewsResponse.notYet(originTradeDate);
		}

		NewsSummaryScope scope = resolveScope(now);
		List<NewsItem> items = instrumentNewsQueryReader.readStockItems(instrumentId, originTradeDate, scope, now);

		if (items.isEmpty()) {
			return InstrumentNewsResponse.of(
				originTradeDate, scope, FeedbackContentStatus.EMPTY, null, List.of());
		}

		AtomicBoolean summaryRowFound = new AtomicBoolean();
		Optional<String> text = feedbackQueryCache.getOrLoadStockSummaryText(
			instrumentId, originTradeDate, scope,
			() -> {
				SummaryTextLookupDto lookup = instrumentNewsQueryReader
					.readStockSummary(instrumentId, originTradeDate, scope);
				summaryRowFound.set(lookup.rowExists());
				return lookup.readyText();
			});
		if (text.isPresent()) {
			return InstrumentNewsResponse.of(
				originTradeDate, scope, FeedbackContentStatus.READY, text.get(), items);
		}

		return InstrumentNewsResponse.of(
			originTradeDate,
			scope,
			summaryRowFound.get() ? FeedbackContentStatus.UNAVAILABLE : FeedbackContentStatus.EMPTY,
			null,
			items);
	}

	private InstrumentNewsResponse getCryptoNews(Long instrumentId) {
		List<NewsItem> items = instrumentNewsQueryReader.readCryptoItems(instrumentId, LocalDateTime.now(clock));
		if (items.isEmpty()) {
			return InstrumentNewsResponse.of(
				null, NewsSummaryScope.ROLLING_24H, FeedbackContentStatus.EMPTY, null, List.of());
		}

		AtomicBoolean summaryRowFound = new AtomicBoolean();
		Optional<String> text = feedbackQueryCache.getOrLoadCryptoSummaryText(instrumentId, () -> {
			SummaryTextLookupDto lookup = instrumentNewsQueryReader.readLatestCryptoSummary(instrumentId);
			summaryRowFound.set(lookup.rowExists());
			return lookup.readyText();
		});
		if (text.isPresent()) {
			return InstrumentNewsResponse.of(
				null, NewsSummaryScope.ROLLING_24H, FeedbackContentStatus.READY, text.get(), items);
		}

		return InstrumentNewsResponse.of(
			null,
			NewsSummaryScope.ROLLING_24H,
			summaryRowFound.get() ? FeedbackContentStatus.UNAVAILABLE : FeedbackContentStatus.EMPTY,
			null,
			items);
	}

	private static NewsSummaryScope resolveScope(LocalTime now) {
		return now.isBefore(MarketSessionTimes.MARKET_CLOSE_TIME)
			? NewsSummaryScope.PRE_MARKET
			: NewsSummaryScope.FULL;
	}
}
