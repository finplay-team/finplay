package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

public final class TutorialScenarioPriceGuideRangeCalculator {

	private static final BigDecimal MARGIN_RATIO = new BigDecimal("0.05");
	private static final BigDecimal UNIT_SPAN_FRACTION = new BigDecimal("0.10");

	private TutorialScenarioPriceGuideRangeCalculator() {}

	public record Range(BigDecimal low, BigDecimal high) {
	}

	public static Optional<Range> calculate(TutorialScenarioScript script) {
		BigDecimal minRatio = null;
		BigDecimal maxRatio = null;
		for (TutorialScenarioStage stage : script.stages()) {
			for (BigDecimal ratio : stage.ratios()) {
				if (minRatio == null || ratio.compareTo(minRatio) < 0) {
					minRatio = ratio;
				}
				if (maxRatio == null || ratio.compareTo(maxRatio) > 0) {
					maxRatio = ratio;
				}
			}
		}
		if (minRatio == null) {
			return Optional.empty();
		}

		BigDecimal basePrice = script.basePrice();
		BigDecimal min = basePrice.multiply(minRatio);
		BigDecimal max = basePrice.multiply(maxRatio);
		BigDecimal span = max.subtract(min);
		if (span.signum() <= 0) {
			return Optional.empty();
		}
		BigDecimal margin = span.multiply(MARGIN_RATIO);
		BigDecimal unit = unit(span);

		BigDecimal low = min.add(margin).divide(unit, 0, RoundingMode.CEILING).multiply(unit).setScale(8,
			RoundingMode.HALF_UP);
		BigDecimal high = max.subtract(margin).divide(unit, 0, RoundingMode.FLOOR).multiply(unit).setScale(8,
			RoundingMode.HALF_UP);
		if (low.compareTo(high) >= 0) {
			return Optional.empty();
		}
		return Optional.of(new Range(low, high));
	}

	private static BigDecimal unit(BigDecimal span) {
		double tenPercent = span.multiply(UNIT_SPAN_FRACTION).doubleValue();
		int exponent = (int)Math.floor(Math.log10(tenPercent));
		return BigDecimal.ONE.scaleByPowerOfTen(exponent);
	}
}
