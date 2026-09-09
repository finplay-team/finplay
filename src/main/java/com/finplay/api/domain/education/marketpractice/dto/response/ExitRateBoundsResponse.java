package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import java.math.BigDecimal;

public record ExitRateBoundsResponse(
	BigDecimal stopLossMin, BigDecimal stopLossMax, BigDecimal takeProfitMin, BigDecimal takeProfitMax) {

	private static final ExitRateBoundsResponse CURRENT = new ExitRateBoundsResponse(
		ExitRates.STOP_LOSS_MIN, ExitRates.STOP_LOSS_MAX, ExitRates.TAKE_PROFIT_MIN, ExitRates.TAKE_PROFIT_MAX);

	public static ExitRateBoundsResponse current() {
		return CURRENT;
	}
}
