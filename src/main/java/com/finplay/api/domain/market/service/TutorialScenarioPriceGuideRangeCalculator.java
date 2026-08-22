// 대본의 배율 극값으로 프론트 안내용 가격 범위를 계산하는 순수 함수(049 plan §5)
package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

// 기준가·극값 배율에 맞춰 단위를 스스로 정하는 일반식이다 — 상수 1,000을 박지 않는다. 기준가가 다른
// 대본(다른 시장·다른 단계)이 들어와도 그대로 맞는다.
public final class TutorialScenarioPriceGuideRangeCalculator {

	private static final BigDecimal MARGIN_RATIO = new BigDecimal("0.05");
	private static final BigDecimal UNIT_SPAN_FRACTION = new BigDecimal("0.10");

	private TutorialScenarioPriceGuideRangeCalculator() {}

	public record Range(BigDecimal low, BigDecimal high) {
	}

	// 폭이 좁아 low >= high가 되는 대본(사건이 있어 극값이 넓은 041류)은 범위 없음이다.
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

	// unit = 10^floor(log10(span * 0.10)) — span의 10% 이하인 가장 큰 10의 거듭제곱.
	private static BigDecimal unit(BigDecimal span) {
		double tenPercent = span.multiply(UNIT_SPAN_FRACTION).doubleValue();
		int exponent = (int)Math.floor(Math.log10(tenPercent));
		return BigDecimal.ONE.scaleByPowerOfTen(exponent);
	}
}
