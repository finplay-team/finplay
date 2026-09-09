package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.dto.response.CounterfactualScenario;
import com.finplay.api.domain.feedback.dto.response.Counterfactuals;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.dto.response.PostSellFlow;
import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.service.StockCandleDto;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class StockPostSellFeedbackReader {

	private static final BigDecimal STOCK_FEE_RATE = PostSellArithmetic.feeRateOf(Market.STOCK);

	private static final LocalTime PAST_SERVICE_DATE_CUTOFF = LocalTime.MAX.withNano(0);

	private final StockReplayService stockReplayService;

	private final PriceMoveEventRepository priceMoveEventRepository;

	private final PriceMoveSourceLoader priceMoveSourceLoader;

	private final PriceMovePeerStatRepository priceMovePeerStatRepository;

	private final Clock clock;

	@Transactional(readOnly = true)
	PostSellFeedbackResponse read(Trade trade, SellAllocationSummaryDto allocation) {
		LocalDate sellSourceTradingDate = sourceTradingDateOf(trade);
		LocalDateTime buyAt = atOriginTradeDate(
			allocation.earliestBuyAt(), allocation.earliestBuySourceTradingDate());
		LocalDateTime sellAt = atOriginTradeDate(trade.getExecutedAt(), sellSourceTradingDate);

		boolean sameSessionCompleted = isSameSessionCompleted(sellSourceTradingDate, allocation, buyAt, sellAt);
		List<HeldPriceMoveItem> priceMoves = sameSessionCompleted
			? findHeldPriceMoves(trade, sellSourceTradingDate, buyAt, sellAt)
			: List.of();

		List<StockCandleDto> fullDayCandles = sameSessionCompleted
			? stockReplayService.getFullDayCandles(trade.getInstrument().getId(), sellSourceTradingDate)
			: List.of();
		HoldExtremes extremes = sameSessionCompleted
			? findHoldExtremes(fullDayCandles, trade.getPrice(), sellSourceTradingDate, buyAt, sellAt)
			: HoldExtremes.absent();

		boolean marketClosed = isAfterMarketClose(serviceDateOf(trade));

		return new PostSellFeedbackResponse(
			trade.getId(),
			trade.getInstrument().getId(),
			trade.getInstrument().getSymbol(),
			trade.getInstrument().getName(),
			buyAt,
			sellAt,
			allocation.buyPrice(),
			trade.getPrice(),
			trade.getQuantity(),
			trade.getFee(),
			trade.getRealizedPnl(),
			returnRate(trade, allocation),
			holdingMinutes(buyAt, sellAt),
			sameSessionCompleted,
			extremes.holdHighPrice(),
			extremes.holdHighAt(),
			extremes.holdLowPrice(),
			extremes.holdLowAt(),
			extremes.sellVsHighRate(),
			extremes.sellVsLowRate(),
			extremes.basis(),
			sameSessionCompleted ? buyToNewsMinutes(buyAt, priceMoves) : null,
			priceMoves,
			sameSessionCompleted
				? buildPostSellFlow(marketClosed, fullDayCandles, trade.getPrice(), sellSourceTradingDate, sellAt)
				: null,
			sameSessionCompleted
				? buildCounterfactuals(
					marketClosed, fullDayCandles, sellSourceTradingDate, extremes, priceMoves,
					trade.getQuantity(), allocation.allocatedCost() + allocation.allocatedBuyFee())
				: null,
			sameSessionCompleted ? buildPeerComparison(priceMoves, serviceDateOf(trade)) : null,
			null,
			null,
			null);
	}

	private static BigDecimal returnRate(Trade trade, SellAllocationSummaryDto allocation) {
		return PostSellArithmetic.returnRate(
			trade.getRealizedPnl(), allocation.allocatedCost() + allocation.allocatedBuyFee());
	}

	private static Integer holdingMinutes(LocalDateTime buyAt, LocalDateTime sellAt) {
		return isReversed(buyAt, sellAt) ? null : PostSellArithmetic.minutesBetween(buyAt, sellAt);
	}

	private static boolean isReversed(LocalDateTime buyAt, LocalDateTime sellAt) {
		return sellAt.isBefore(buyAt);
	}

	private static HoldExtremes findHoldExtremes(
		List<StockCandleDto> fullDayCandles,
		BigDecimal sellPrice,
		LocalDate sourceTradingDate,
		LocalDateTime buyAt,
		LocalDateTime sellAt) {
		LocalTime from = PostSellArithmetic.candleBoundary(buyAt);
		LocalTime to = PostSellArithmetic.candleBoundary(sellAt);
		List<StockCandleDto> candles = fullDayCandles.stream()
			.filter(candle -> !candle.candleTime().isBefore(from) && !candle.candleTime().isAfter(to))
			.toList();
		if (candles.isEmpty()) {
			return HoldExtremes.absent();
		}

		StockCandleDto high = candles.get(0);
		StockCandleDto low = candles.get(0);
		for (StockCandleDto candle : candles) {
			if (candle.close().compareTo(high.close()) > 0) {
				high = candle;
			}
			if (candle.close().compareTo(low.close()) < 0) {
				low = candle;
			}
		}

		return new HoldExtremes(
			high.close(),
			LocalDateTime.of(sourceTradingDate, high.candleTime()),
			low.close(),
			LocalDateTime.of(sourceTradingDate, low.candleTime()),
			PostSellArithmetic.rateAgainst(sellPrice, high.close()),
			PostSellArithmetic.rateAgainst(sellPrice, low.close()),
			HoldHighBasis.MINUTE);
	}

	private boolean isAfterMarketClose(LocalDate serviceDate) {
		if (serviceDate == null) {
			return false;
		}
		return !LocalDateTime.now(clock)
			.isBefore(LocalDateTime.of(serviceDate, MarketSessionTimes.MARKET_CLOSE_TIME));
	}

	private static PostSellFlow buildPostSellFlow(
		boolean marketClosed,
		List<StockCandleDto> fullDayCandles,
		BigDecimal sellPrice,
		LocalDate sourceTradingDate,
		LocalDateTime sellAt) {
		if (!marketClosed) {
			return new PostSellFlow(PostSellFeedbackStatus.NOT_YET, null, null, null, null, null);
		}

		StockCandleDto lastCandle = lastCandle(fullDayCandles);
		StockCandleDto postSellHigh = highestCloseAfter(fullDayCandles, PostSellArithmetic.candleBoundary(sellAt));
		return new PostSellFlow(
			PostSellFeedbackStatus.READY,
			lastCandle == null ? null : lastCandle.close(),
			lastCandle == null ? null : LocalDateTime.of(sourceTradingDate, lastCandle.candleTime()),
			lastCandle == null ? null : PostSellArithmetic.rateAgainst(lastCandle.close(), sellPrice),
			postSellHigh == null ? null : postSellHigh.close(),
			postSellHigh == null ? null : LocalDateTime.of(sourceTradingDate, postSellHigh.candleTime()));
	}

	private static Counterfactuals buildCounterfactuals(
		boolean marketClosed,
		List<StockCandleDto> fullDayCandles,
		LocalDate sourceTradingDate,
		HoldExtremes extremes,
		List<HeldPriceMoveItem> priceMoves,
		BigDecimal quantity,
		long buyBasis) {
		if (!marketClosed) {
			return new Counterfactuals(PostSellFeedbackStatus.NOT_YET, null, null, null);
		}
		return new Counterfactuals(
			PostSellFeedbackStatus.READY,
			scenarioAtClose(fullDayCandles, sourceTradingDate, quantity, buyBasis),
			scenarioAtHoldHigh(extremes, quantity, buyBasis),
			scenarioAtFirstMoveAfterBuy(fullDayCandles, priceMoves, quantity, buyBasis));
	}

	private static CounterfactualScenario scenarioAtClose(
		List<StockCandleDto> fullDayCandles, LocalDate sourceTradingDate, BigDecimal quantity, long buyBasis) {
		StockCandleDto lastCandle = lastCandle(fullDayCandles);
		return lastCandle == null
			? null
			: new CounterfactualScenario(
				lastCandle.close(), LocalDateTime.of(sourceTradingDate, lastCandle.candleTime()),
				PostSellArithmetic.counterfactualReturnRate(lastCandle.close(), quantity, buyBasis, STOCK_FEE_RATE));
	}

	private static CounterfactualScenario scenarioAtHoldHigh(
		HoldExtremes extremes, BigDecimal quantity, long buyBasis) {
		return extremes.holdHighPrice() == null
			? null
			: new CounterfactualScenario(extremes.holdHighPrice(), extremes.holdHighAt(),
				PostSellArithmetic.counterfactualReturnRate(
					extremes.holdHighPrice(), quantity, buyBasis, STOCK_FEE_RATE));
	}

	private static CounterfactualScenario scenarioAtFirstMoveAfterBuy(
		List<StockCandleDto> fullDayCandles, List<HeldPriceMoveItem> priceMoves, BigDecimal quantity,
		long buyBasis) {
		if (priceMoves.isEmpty()) {
			return null;
		}
		LocalDateTime windowEnd = priceMoves.get(0).windowEnd();
		return fullDayCandles.stream()
			.filter(candle -> candle.candleTime().equals(windowEnd.toLocalTime()))
			.findFirst()
			.map(candle -> new CounterfactualScenario(
				candle.close(),
				windowEnd,
				PostSellArithmetic.counterfactualReturnRate(candle.close(), quantity, buyBasis, STOCK_FEE_RATE)))
			.orElse(null);
	}

	private static StockCandleDto lastCandle(List<StockCandleDto> fullDayCandles) {
		return fullDayCandles.isEmpty() ? null : fullDayCandles.get(fullDayCandles.size() - 1);
	}

	private static StockCandleDto highestCloseAfter(List<StockCandleDto> fullDayCandles, LocalTime sellTime) {
		StockCandleDto highest = null;
		for (StockCandleDto candle : fullDayCandles) {
			if (!candle.candleTime().isAfter(sellTime)) {
				continue;
			}
			if (highest == null || candle.close().compareTo(highest.close()) > 0) {
				highest = candle;
			}
		}
		return highest;
	}

	private PeerComparison buildPeerComparison(List<HeldPriceMoveItem> priceMoves, LocalDate sellServiceDate) {
		if (priceMoves.isEmpty()) {
			return PostSellArithmetic.peerComparisonNoEvent();
		}

		HeldPriceMoveItem card = priceMoves.get(0);
		Integer yourMinutesToSell = card.minutesBeforeSell();
		return priceMovePeerStatRepository
			.findByPriceMoveEventIdAndServiceDate(card.id(), sellServiceDate)
			.map(stat -> PostSellArithmetic.toPeerComparison(stat, card.id(), yourMinutesToSell))
			.orElseGet(PostSellArithmetic::peerComparisonNotYet);
	}

	private List<HeldPriceMoveItem> findHeldPriceMoves(
		Trade trade, LocalDate sourceTradingDate, LocalDateTime buyAt, LocalDateTime sellAt) {
		LocalTime revealCutoff = revealCutoff(serviceDateOf(trade));
		if (revealCutoff == null) {
			return List.of();
		}

		List<PriceMoveEvent> events = priceMoveEventRepository
			.findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				trade.getInstrument().getId(),
				sourceTradingDate,
				PostSellArithmetic.candleBoundary(buyAt),
				PostSellArithmetic.candleBoundary(sellAt),
				revealCutoff);
		if (events.isEmpty()) {
			return List.of();
		}

		Map<Long, List<NewsItem>> sourcesByEventId = priceMoveSourceLoader.findSources(events);
		return events.stream()
			.map(event -> toHeldPriceMoveItem(
				event, buyAt, sellAt, sourcesByEventId.getOrDefault(event.getId(), List.of())))
			.toList();
	}

	private LocalTime revealCutoff(LocalDate serviceDate) {
		if (serviceDate == null) {
			return null;
		}
		LocalDate today = LocalDate.now(clock);
		if (serviceDate.isBefore(today)) {
			return PAST_SERVICE_DATE_CUTOFF;
		}
		if (serviceDate.isAfter(today)) {
			return null;
		}
		return LocalTime.now(clock).withNano(0);
	}

	private static HeldPriceMoveItem toHeldPriceMoveItem(
		PriceMoveEvent event, LocalDateTime buyAt, LocalDateTime sellAt, List<NewsItem> sources) {
		LocalDateTime windowStart = LocalDateTime.of(event.getOriginTradeDate(), event.getWindowStart());
		LocalDateTime windowEnd = LocalDateTime.of(event.getOriginTradeDate(), event.getWindowEnd());
		return new HeldPriceMoveItem(
			event.getId(),
			windowStart,
			windowEnd,
			event.getChangeRate(),
			PostSellArithmetic.minutesBetween(buyAt, windowEnd),
			PostSellArithmetic.minutesBetween(windowEnd, sellAt),
			event.getNarrative(),
			sources);
	}

	private static Integer buyToNewsMinutes(LocalDateTime buyAt, List<HeldPriceMoveItem> priceMoves) {
		return priceMoves.stream()
			.flatMap(move -> move.sources().stream())
			.map(NewsItem::publishedAt)
			.min(Comparator.naturalOrder())
			.map(firstNewsAt -> PostSellArithmetic.minutesBetween(buyAt, firstNewsAt))
			.orElse(null);
	}

	private boolean isSameSessionCompleted(
		LocalDate sellSourceTradingDate,
		SellAllocationSummaryDto allocation,
		LocalDateTime buyAt,
		LocalDateTime sellAt) {
		if (sellSourceTradingDate == null) {
			return false;
		}
		if (isReversed(buyAt, sellAt)) {
			return false;
		}
		return allocation.buySourceTradingDates().stream().allMatch(sellSourceTradingDate::equals);
	}

	private LocalDate sourceTradingDateOf(Trade trade) {
		StockReplaySession session = trade.getStockReplaySession();
		return session == null ? null : session.getSourceTradingDate();
	}

	private static LocalDate serviceDateOf(Trade trade) {
		StockReplaySession session = trade.getStockReplaySession();
		return session == null ? null : session.getServiceDate();
	}

	private static LocalDateTime atOriginTradeDate(LocalDateTime executedAt, LocalDate originTradeDate) {
		return originTradeDate == null ? executedAt : LocalDateTime.of(originTradeDate, executedAt.toLocalTime());
	}

}
