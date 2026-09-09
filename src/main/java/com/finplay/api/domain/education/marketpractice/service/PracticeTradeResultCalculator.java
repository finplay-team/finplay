package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTradeResultResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeSellCause;
import com.finplay.api.domain.education.marketpractice.entity.PracticeSellVerdict;
import java.math.BigDecimal;
import java.math.RoundingMode;

final class PracticeTradeResultCalculator {

	private static final int RETURN_RATE_SCALE = 4;

	private PracticeTradeResultCalculator() {}

	static PracticeTradeResultResponse calculate(
		BigDecimal buyPrice,
		BigDecimal sellPrice,
		Long realizedPnl,
		Long soldBuyBasis,
		BigDecimal stopLossPrice,
		BigDecimal takeProfitPrice,
		PracticeSellCause sellCause) {
		PracticeSellVerdict verdict = judgeSellVerdict(sellPrice, stopLossPrice, takeProfitPrice);
		return new PracticeTradeResultResponse(
			buyPrice,
			sellPrice,
			realizedPnl,
			returnRate(realizedPnl, soldBuyBasis),
			verdict == null ? null : verdict.name(),
			sellCause == null ? null : sellCause.name());
	}

	private static BigDecimal returnRate(Long realizedPnl, Long soldBuyBasis) {
		if (realizedPnl == null || soldBuyBasis == null || soldBuyBasis <= 0L) {
			return null;
		}
		return BigDecimal.valueOf(realizedPnl)
			.divide(BigDecimal.valueOf(soldBuyBasis), RETURN_RATE_SCALE, RoundingMode.HALF_UP);
	}

	private static PracticeSellVerdict judgeSellVerdict(
		BigDecimal sellPrice, BigDecimal stopLossPrice, BigDecimal takeProfitPrice) {
		if (sellPrice == null || stopLossPrice == null || takeProfitPrice == null) {
			return null;
		}
		if (sellPrice.compareTo(takeProfitPrice) >= 0) {
			return PracticeSellVerdict.ABOVE_TAKE_PROFIT;
		}
		if (sellPrice.compareTo(stopLossPrice) <= 0) {
			return PracticeSellVerdict.BELOW_STOP_LOSS;
		}
		return PracticeSellVerdict.BETWEEN_LINES;
	}
}
