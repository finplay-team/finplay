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
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.CandleInterval;
import com.finplay.api.domain.market.service.CandleQueryService;
import com.finplay.api.domain.market.service.CryptoCandleDto;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class CryptoPostSellFeedbackReader {

	private static final int MAX_MINUTE_SPAN_MINUTES = 199;

	private static final LocalTime DAY_END_LABEL = LocalTime.of(23, 59);

	private final CandleQueryService candleQueryService;

	private final CryptoPostSellFeedbackDbReader cryptoPostSellFeedbackDbReader;

	private final Clock clock;

	PostSellFeedbackResponse read(Trade trade, SellAllocationSummaryDto allocation) {
		String symbol = trade.getInstrument().getSymbol();
		LocalDateTime buyAt = allocation.earliestBuyAt();
		LocalDateTime sellAt = trade.getExecutedAt();
		long buyBasis = allocation.allocatedCost() + allocation.allocatedBuyFee();

		List<HeldPriceMoveItem> priceMoves = cryptoPostSellFeedbackDbReader.findHeldPriceMoves(trade, buyAt,
			sellAt);

		HoldExtremes extremes = findHoldExtremes(symbol, trade.getPrice(), buyAt, sellAt);
		boolean dayClosed = isAfterDayClose(sellAt);
		BigDecimal sellDayClose = dayClosed ? sellDayClose(symbol, sellAt) : null;
		PostSellFlow postSellFlow = buildPostSellFlow(symbol, dayClosed, sellDayClose, trade.getPrice(), sellAt);
		Counterfactuals counterfactuals = buildCounterfactuals(
			symbol, dayClosed, sellDayClose, extremes, priceMoves, trade.getQuantity(), buyBasis, sellAt);

		PeerComparison peerComparison = cryptoPostSellFeedbackDbReader.buildPeerComparison(priceMoves);

		return new PostSellFeedbackResponse(
			trade.getId(),
			trade.getInstrument().getId(),
			symbol,
			trade.getInstrument().getName(),
			buyAt,
			sellAt,
			allocation.buyPrice(),
			trade.getPrice(),
			trade.getQuantity(),
			trade.getFee(),
			trade.getRealizedPnl(),
			PostSellArithmetic.returnRate(trade.getRealizedPnl(), buyBasis),
			PostSellArithmetic.minutesBetween(buyAt, sellAt),
			true,
			extremes.holdHighPrice(),
			extremes.holdHighAt(),
			extremes.holdLowPrice(),
			extremes.holdLowAt(),
			extremes.sellVsHighRate(),
			extremes.sellVsLowRate(),
			extremes.basis(),
			buyToNewsMinutes(buyAt, priceMoves),
			priceMoves,
			postSellFlow,
			counterfactuals,
			peerComparison,
			null,
			null,
			null);
	}

	private boolean isAfterDayClose(LocalDateTime sellAt) {
		return !LocalDateTime.now(clock).isBefore(sellAt.toLocalDate().plusDays(1).atStartOfDay());
	}

	private HoldExtremes findHoldExtremes(
		String symbol, BigDecimal sellPrice, LocalDateTime buyAt, LocalDateTime sellAt) {
		if (PostSellArithmetic.minutesBetween(buyAt, sellAt) <= MAX_MINUTE_SPAN_MINUTES) {
			LocalDateTime from = PostSellArithmetic.onMinuteBoundary(buyAt);
			LocalDateTime to = PostSellArithmetic.onMinuteBoundary(sellAt);
			return extremesOf(
				minuteCandlesWithin(symbol, from, to), sellPrice, HoldHighBasis.MINUTE, CryptoCandleDto::sourceTime);
		}
		return extremesOf(
			dailyCandlesWithinHold(symbol, buyAt, sellAt),
			sellPrice,
			HoldHighBasis.DAILY,
			candle -> LocalDateTime.of(candle.sourceTime().toLocalDate(), DAY_END_LABEL));
	}

	private List<CryptoCandleDto> minuteCandlesWithin(String symbol, LocalDateTime from, LocalDateTime to) {
		return candles(symbol, CandleInterval.ONE_MINUTE, from, to).stream()
			.filter(candle -> !candle.sourceTime().isBefore(from) && !candle.sourceTime().isAfter(to))
			.toList();
	}

	private List<CryptoCandleDto> candles(
		String symbol, CandleInterval interval, LocalDateTime from, LocalDateTime to) {
		try {
			return candleQueryService.getCryptoCandles(symbol, interval, from, to);
		} catch (BusinessException ex) {
			if (ex.getErrorCode() != ErrorCode.MARKET_DATA_PROVIDER_ERROR) {
				throw ex;
			}
			log.warn("코인 봉 조회에 실패해 관련 값을 비운 채 회고를 만든다. 종목={} 간격={} 구간={}~{}",
				symbol, interval, from, to, ex);
			return List.of();
		}
	}

	private List<CryptoCandleDto> dailyCandlesWithinHold(
		String symbol, LocalDateTime buyAt, LocalDateTime sellAt) {
		LocalDate firstDay = buyAt.toLocalDate();
		LocalDate lastDay = sellAt.toLocalDate().minusDays(1);
		if (lastDay.isBefore(firstDay)) {
			return List.of();
		}
		return candles(symbol, CandleInterval.ONE_DAY, firstDay.atStartOfDay(), lastDay.atStartOfDay())
			.stream()
			.filter(candle -> !candle.sourceTime().toLocalDate().isBefore(firstDay))
			.filter(candle -> !candle.sourceTime().toLocalDate().isAfter(lastDay))
			.toList();
	}

	private static HoldExtremes extremesOf(
		List<CryptoCandleDto> candles,
		BigDecimal sellPrice,
		HoldHighBasis basis,
		Function<CryptoCandleDto, LocalDateTime> at) {
		if (candles.isEmpty()) {
			return HoldExtremes.absent();
		}

		CryptoCandleDto high = candles.get(0);
		CryptoCandleDto low = candles.get(0);
		for (CryptoCandleDto candle : candles) {
			if (candle.close().compareTo(high.close()) > 0) {
				high = candle;
			}
			if (candle.close().compareTo(low.close()) < 0) {
				low = candle;
			}
		}

		return new HoldExtremes(
			high.close(),
			at.apply(high),
			low.close(),
			at.apply(low),
			PostSellArithmetic.rateAgainst(sellPrice, high.close()),
			PostSellArithmetic.rateAgainst(sellPrice, low.close()),
			basis);
	}

	private PostSellFlow buildPostSellFlow(
		String symbol, boolean dayClosed, BigDecimal closePrice, BigDecimal sellPrice, LocalDateTime sellAt) {
		if (!dayClosed) {
			return new PostSellFlow(PostSellFeedbackStatus.NOT_YET, null, null, null, null, null);
		}

		CryptoCandleDto postSellHigh = highestCloseAfterSell(symbol, sellAt);
		return new PostSellFlow(
			PostSellFeedbackStatus.READY,
			closePrice,
			closePrice == null ? null : LocalDateTime.of(sellAt.toLocalDate(), DAY_END_LABEL),
			PostSellArithmetic.rateAgainst(closePrice, sellPrice),
			postSellHigh == null ? null : postSellHigh.close(),
			postSellHigh == null ? null : postSellHigh.sourceTime());
	}

	private BigDecimal sellDayClose(String symbol, LocalDateTime sellAt) {
		LocalDate sellDate = sellAt.toLocalDate();
		return candles(symbol, CandleInterval.ONE_DAY, sellDate.atStartOfDay(), sellDate.atStartOfDay())
			.stream()
			.filter(candle -> candle.sourceTime().toLocalDate().equals(sellDate))
			.findFirst()
			.map(CryptoCandleDto::close)
			.orElse(null);
	}

	private CryptoCandleDto highestCloseAfterSell(String symbol, LocalDateTime sellAt) {
		LocalDateTime from = PostSellArithmetic.onMinuteBoundary(sellAt).plusMinutes(1);
		LocalDateTime to = LocalDateTime.of(sellAt.toLocalDate(), DAY_END_LABEL);
		if (from.isAfter(to) || PostSellArithmetic.minutesBetween(from, to) > MAX_MINUTE_SPAN_MINUTES) {
			return null;
		}
		return minuteCandlesWithin(symbol, from, to).stream()
			.reduce((left, right) -> right.close().compareTo(left.close()) > 0 ? right : left)
			.orElse(null);
	}

	private Counterfactuals buildCounterfactuals(
		String symbol,
		boolean dayClosed,
		BigDecimal closePrice,
		HoldExtremes extremes,
		List<HeldPriceMoveItem> priceMoves,
		BigDecimal quantity,
		long buyBasis,
		LocalDateTime sellAt) {
		if (!dayClosed) {
			return new Counterfactuals(PostSellFeedbackStatus.NOT_YET, null, null, null);
		}

		BigDecimal feeRate = PostSellArithmetic.feeRateOf(Market.CRYPTO);
		return new Counterfactuals(
			PostSellFeedbackStatus.READY,
			closePrice == null
				? null
				: new CounterfactualScenario(
					closePrice,
					LocalDateTime.of(sellAt.toLocalDate(), DAY_END_LABEL),
					PostSellArithmetic.counterfactualReturnRate(closePrice, quantity, buyBasis, feeRate)),
			extremes.holdHighPrice() == null
				? null
				: new CounterfactualScenario(
					extremes.holdHighPrice(),
					extremes.holdHighAt(),
					PostSellArithmetic.counterfactualReturnRate(
						extremes.holdHighPrice(), quantity, buyBasis, feeRate)),
			scenarioAtFirstMoveAfterBuy(symbol, priceMoves, quantity, buyBasis, feeRate));
	}

	private CounterfactualScenario scenarioAtFirstMoveAfterBuy(
		String symbol,
		List<HeldPriceMoveItem> priceMoves,
		BigDecimal quantity,
		long buyBasis,
		BigDecimal feeRate) {
		if (priceMoves.isEmpty()) {
			return null;
		}
		LocalDateTime at = PostSellArithmetic.onMinuteBoundary(priceMoves.get(0).windowEnd());
		return candles(symbol, CandleInterval.ONE_MINUTE, at, at).stream()
			.filter(candle -> PostSellArithmetic.onMinuteBoundary(candle.sourceTime()).equals(at))
			.findFirst()
			.map(candle -> new CounterfactualScenario(
				candle.close(),
				at,
				PostSellArithmetic.counterfactualReturnRate(candle.close(), quantity, buyBasis, feeRate)))
			.orElse(null);
	}

	private static Integer buyToNewsMinutes(LocalDateTime buyAt, List<HeldPriceMoveItem> priceMoves) {
		return priceMoves.stream()
			.flatMap(move -> move.sources().stream())
			.map(NewsItem::publishedAt)
			.min(Comparator.naturalOrder())
			.map(firstNewsAt -> PostSellArithmetic.minutesBetween(buyAt, firstNewsAt))
			.orElse(null);
	}

}
