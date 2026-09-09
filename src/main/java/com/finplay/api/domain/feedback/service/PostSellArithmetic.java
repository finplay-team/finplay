package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMovePeerStat;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

final class PostSellArithmetic {

	static final int RETURN_RATE_SCALE = 4;

	static final int DERIVED_RATE_SCALE = 4;

	static final int PEER_RATE_SCALE = 4;

	private static final BigDecimal STOCK_FEE_RATE = new BigDecimal("0.00015");

	private static final BigDecimal CRYPTO_FEE_RATE = new BigDecimal("0.0005");

	private static final int MIN_PEER_SAMPLE = 5;

	private PostSellArithmetic() {}

	public static BigDecimal feeRateOf(Market market) {
		return market == Market.CRYPTO ? CRYPTO_FEE_RATE : STOCK_FEE_RATE;
	}

	public static BigDecimal returnRate(Long realizedPnl, long buyBasis) {
		if (buyBasis == 0L || realizedPnl == null) {
			return BigDecimal.ZERO;
		}
		return BigDecimal.valueOf(realizedPnl)
			.divide(BigDecimal.valueOf(buyBasis), RETURN_RATE_SCALE, RoundingMode.HALF_UP);
	}

	public static BigDecimal rateAgainst(BigDecimal price, BigDecimal basePrice) {
		if (price == null || basePrice == null || basePrice.signum() == 0) {
			return null;
		}
		return price.subtract(basePrice).divide(basePrice, DERIVED_RATE_SCALE, RoundingMode.HALF_UP);
	}

	public static BigDecimal counterfactualReturnRate(
		BigDecimal price, BigDecimal quantity, long buyBasis, BigDecimal feeRate) {
		long amount = price.multiply(quantity).setScale(0, RoundingMode.FLOOR).longValueExact();
		long fee = BigDecimal.valueOf(amount)
			.multiply(feeRate)
			.setScale(0, RoundingMode.FLOOR)
			.longValueExact();
		if (buyBasis == 0L) {
			return BigDecimal.ZERO;
		}
		long realizedPnl = (amount - fee) - buyBasis;
		return BigDecimal.valueOf(realizedPnl)
			.divide(BigDecimal.valueOf(buyBasis), RETURN_RATE_SCALE, RoundingMode.HALF_UP);
	}

	public static int minutesBetween(LocalDateTime from, LocalDateTime to) {
		return (int)ChronoUnit.MINUTES.between(onMinuteBoundary(from), onMinuteBoundary(to));
	}

	public static LocalTime candleBoundary(LocalDateTime executedAt) {
		return onMinuteBoundary(executedAt).toLocalTime();
	}

	public static LocalDateTime onMinuteBoundary(LocalDateTime executedAt) {
		return executedAt.truncatedTo(ChronoUnit.MINUTES);
	}

	public static PeerComparison toPeerComparison(
		PriceMovePeerStat stat, Long priceMoveId, Integer yourMinutesToSell) {
		if (stat.getHolderCount() < MIN_PEER_SAMPLE) {
			return new PeerComparison(
				PostSellFeedbackStatus.INSUFFICIENT_SAMPLE, priceMoveId, null, null, null, yourMinutesToSell);
		}
		return new PeerComparison(
			PostSellFeedbackStatus.READY,
			priceMoveId,
			stat.getHolderCount(),
			soldWithin30MinRate(stat),
			stat.getMedianMinutesToSell(),
			yourMinutesToSell);
	}

	private static BigDecimal soldWithin30MinRate(PriceMovePeerStat stat) {
		return BigDecimal.valueOf(stat.getSoldWithin30MinCount())
			.divide(BigDecimal.valueOf(stat.getHolderCount()), PEER_RATE_SCALE, RoundingMode.HALF_UP);
	}

	public static PeerComparison peerComparisonNotYet() {
		return new PeerComparison(PostSellFeedbackStatus.NOT_YET, null, null, null, null, null);
	}

	public static PeerComparison peerComparisonNoEvent() {
		return new PeerComparison(PostSellFeedbackStatus.NO_EVENT, null, null, null, null, null);
	}
}
