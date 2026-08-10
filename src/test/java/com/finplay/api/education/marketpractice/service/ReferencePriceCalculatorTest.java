// ReferencePriceCalculator의 PRICE/PERCENT 참조 가격선 계산과 반올림 규칙을 검증하는 단위 테스트다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReferencePriceCalculatorTest {

	private final ReferencePriceCalculator calculator = new ReferencePriceCalculator();

	@Test
	void calculateFromPriceReturnsStoredAbsolutePricesNormalizedToScale8() {
		Optional<ReferencePriceLines> result = calculator.calculateFromPrice(new BigDecimal("90"),
			new BigDecimal("110"));

		assertThat(result).isPresent();
		assertThat(result.get().referenceStopLossPrice()).isEqualByComparingTo(new BigDecimal("90.00000000"));
		assertThat(result.get().referenceTakeProfitPrice()).isEqualByComparingTo(new BigDecimal("110.00000000"));
		assertThat(result.get().referenceStopLossPrice().scale()).isEqualTo(8);
		assertThat(result.get().referenceTakeProfitPrice().scale()).isEqualTo(8);
	}

	@Test
	void calculateFromPriceReturnsEmptyWhenStopLossMissing() {
		Optional<ReferencePriceLines> result = calculator.calculateFromPrice(null, new BigDecimal("110"));

		assertThat(result).isEmpty();
	}

	@Test
	void calculateFromPriceReturnsEmptyWhenTakeProfitMissing() {
		Optional<ReferencePriceLines> result = calculator.calculateFromPrice(new BigDecimal("90"), null);

		assertThat(result).isEmpty();
	}

	@Test
	void calculateReturnsEmptyWhenChainIsNull() {
		Optional<ReferencePriceLines> result = calculator.calculate(null);

		assertThat(result).isEmpty();
	}

	@Test
	void calculateDelegatesToCalculateFromPriceUsingChainIntentionValues() {
		ResolvedPracticeChainDto chain = new ResolvedPracticeChainDto(
			1L, null, 2L, null, new BigDecimal("95"), new BigDecimal("105"), 3L, null,
			new BigDecimal("100"), 4L);

		Optional<ReferencePriceLines> result = calculator.calculate(chain);

		assertThat(result).isPresent();
		assertThat(result.get().referenceStopLossPrice()).isEqualByComparingTo(new BigDecimal("95.00000000"));
		assertThat(result.get().referenceTakeProfitPrice()).isEqualByComparingTo(new BigDecimal("105.00000000"));
	}

	@Test
	void calculateFromPercentRoundsResultOnceAtScale8HalfUpWhenDivisionDoesNotTerminateCleanly() {
		// 019 공식: factor = 1 ± rate/100 은 항상 정확히 나눠지지만(100으로 나눔), entryPrice와 곱한 결과는
		// scale 8을 넘어가는 자리가 남을 수 있다 — 이 케이스에서 실제로 반올림이 한 번 일어남을 확인한다.
		// PowerShell decimal(28~29자리 정밀도, exact base-10 연산)로 사전 계산해 검증한 기대값이다.
		BigDecimal entryPrice = new BigDecimal("12345.12345678");
		BigDecimal stopLossRate = new BigDecimal("3.3333");
		BigDecimal takeProfitRate = new BigDecimal("7.777");

		Optional<ReferencePriceLines> result = calculator.calculateFromPercent(entryPrice, stopLossRate,
			takeProfitRate);

		assertThat(result).isPresent();
		assertThat(result.get().referenceStopLossPrice()).isEqualByComparingTo(new BigDecimal("11933.62345660"));
		assertThat(result.get().referenceTakeProfitPrice()).isEqualByComparingTo(new BigDecimal("13305.20370801"));
		assertThat(result.get().referenceStopLossPrice().scale()).isEqualTo(8);
		assertThat(result.get().referenceTakeProfitPrice().scale()).isEqualTo(8);
	}

	@Test
	void calculateFromPercentHandlesRateNearZeroBoundary() {
		BigDecimal entryPrice = new BigDecimal("100");
		BigDecimal stopLossRate = new BigDecimal("0.00000001");
		BigDecimal takeProfitRate = new BigDecimal("0.00000001");

		Optional<ReferencePriceLines> result = calculator.calculateFromPercent(entryPrice, stopLossRate,
			takeProfitRate);

		assertThat(result).isPresent();
		assertThat(result.get().referenceStopLossPrice()).isEqualByComparingTo(new BigDecimal("99.99999999"));
		assertThat(result.get().referenceTakeProfitPrice()).isEqualByComparingTo(new BigDecimal("100.00000001"));
	}

	@Test
	void calculateFromPercentHandlesRateNearHundredBoundary() {
		BigDecimal entryPrice = new BigDecimal("100");
		BigDecimal stopLossRate = new BigDecimal("99.99999999");
		BigDecimal takeProfitRate = new BigDecimal("99.99999999");

		Optional<ReferencePriceLines> result = calculator.calculateFromPercent(entryPrice, stopLossRate,
			takeProfitRate);

		assertThat(result).isPresent();
		assertThat(result.get().referenceStopLossPrice()).isEqualByComparingTo(new BigDecimal("0.00000001"));
		assertThat(result.get().referenceTakeProfitPrice()).isEqualByComparingTo(new BigDecimal("199.99999999"));
	}

	@Test
	void calculateFromPercentHandlesTakeProfitRateNearThousandBoundary() {
		BigDecimal entryPrice = new BigDecimal("100");
		BigDecimal stopLossRate = new BigDecimal("0.00000001");
		BigDecimal takeProfitRate = new BigDecimal("999.99999999");

		Optional<ReferencePriceLines> result = calculator.calculateFromPercent(entryPrice, stopLossRate,
			takeProfitRate);

		assertThat(result).isPresent();
		assertThat(result.get().referenceStopLossPrice()).isEqualByComparingTo(new BigDecimal("99.99999999"));
		assertThat(result.get().referenceTakeProfitPrice()).isEqualByComparingTo(new BigDecimal("1099.99999999"));
	}

	@Test
	void calculateFromPercentReturnsEmptyWhenEntryPriceMissing() {
		Optional<ReferencePriceLines> result = calculator.calculateFromPercent(null, new BigDecimal("3"),
			new BigDecimal("5"));

		assertThat(result).isEmpty();
	}

	@Test
	void calculateFromPercentReturnsEmptyWhenStopLossRateMissing() {
		Optional<ReferencePriceLines> result = calculator.calculateFromPercent(new BigDecimal("100"), null,
			new BigDecimal("5"));

		assertThat(result).isEmpty();
	}

	@Test
	void calculateFromPercentReturnsEmptyWhenTakeProfitRateMissing() {
		Optional<ReferencePriceLines> result = calculator.calculateFromPercent(new BigDecimal("100"),
			new BigDecimal("3"), null);

		assertThat(result).isEmpty();
	}
}
