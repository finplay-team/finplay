package com.finplay.api.domain.education.marketpractice.entity;

import java.math.BigDecimal;
import java.util.Arrays;

public record ExitRates(BigDecimal stopLossRate, BigDecimal takeProfitRate) {

	public static final BigDecimal STOP_LOSS_MIN = new BigDecimal("2");
	public static final BigDecimal STOP_LOSS_MAX = new BigDecimal("5");

	public static final BigDecimal TAKE_PROFIT_MIN = new BigDecimal("3");
	public static final BigDecimal TAKE_PROFIT_MAX = new BigDecimal("8");

	public static final int MAX_SCALE = 1;

	public static final ExitRates DEFAULT = of(ExitPreset.DEFAULT);

	public ExitRates {
		if (stopLossRate == null || takeProfitRate == null) {
			throw new IllegalArgumentException("손절률과 익절률은 모두 필요합니다.");
		}
		requireWithin(stopLossRate, STOP_LOSS_MIN, STOP_LOSS_MAX, "손절률");
		requireWithin(takeProfitRate, TAKE_PROFIT_MIN, TAKE_PROFIT_MAX, "익절률");
		stopLossRate = stopLossRate.stripTrailingZeros();
		takeProfitRate = takeProfitRate.stripTrailingZeros();
	}

	public static ExitRates of(BigDecimal stopLossRate, BigDecimal takeProfitRate) {
		return new ExitRates(stopLossRate, takeProfitRate);
	}

	public static ExitRates of(ExitPreset preset) {
		return new ExitRates(preset.stopLossRate(), preset.takeProfitRate());
	}

	public ExitPreset matchingPreset() {
		return Arrays.stream(ExitPreset.values())
			.filter(preset -> preset.stopLossRate().compareTo(stopLossRate) == 0
				&& preset.takeProfitRate().compareTo(takeProfitRate) == 0)
			.findFirst()
			.orElse(null);
	}

	private static void requireWithin(BigDecimal rate, BigDecimal min, BigDecimal max, String name) {
		if (rate.compareTo(min) < 0 || rate.compareTo(max) > 0) {
			throw new IllegalArgumentException(name + "은(는) " + min + "% 이상 " + max + "% 이하여야 합니다.");
		}
	}
}
