package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
			new BigDecimal("100"), 4L, null, null, false);

		Optional<ReferencePriceLines> result = calculator.calculate(chain);

		assertThat(result).isPresent();
		assertThat(result.get().referenceStopLossPrice()).isEqualByComparingTo(new BigDecimal("95.00000000"));
		assertThat(result.get().referenceTakeProfitPrice()).isEqualByComparingTo(new BigDecimal("105.00000000"));
	}

	@Test
	void calculateFromPercentRoundsResultOnceAtScale8HalfUpWhenDivisionDoesNotTerminateCleanly() {
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

	@ParameterizedTest
	@ValueSource(strings = {
		"100", "51234.12345678", "0.00012345", "3.33333333", "87654321.87654321", "1"})
	void balancedPresetProducesExactlyTheSameLinesAsTheCurrentHardcodedMultipliers(String rawEntryPrice) {
		BigDecimal entryPrice = new BigDecimal(rawEntryPrice).setScale(8, RoundingMode.HALF_UP);

		ReferencePriceLines lines = calculator.calculateFromPreset(entryPrice, ExitPreset.BALANCED);

		assertThat(lines.referenceStopLossPrice())
			.isEqualTo(entryPrice.multiply(currentMultiplier("STOP_LOSS_MULTIPLIER"))
				.setScale(8, RoundingMode.HALF_UP));
		assertThat(lines.referenceTakeProfitPrice())
			.isEqualTo(entryPrice.multiply(currentMultiplier("TAKE_PROFIT_MULTIPLIER"))
				.setScale(8, RoundingMode.HALF_UP));
	}

	private static BigDecimal currentMultiplier(String fieldName) {
		return switch (fieldName) {
			case "STOP_LOSS_MULTIPLIER" -> new BigDecimal("0.97");
			case "TAKE_PROFIT_MULTIPLIER" -> new BigDecimal("1.05");
			default -> throw new IllegalArgumentException("알 수 없는 배율입니다: " + fieldName);
		};
	}

	@Test
	void balancedIsTheDefaultPresetSoUnselectedUsersKeepTheCurrentLines() {
		BigDecimal entryPrice = new BigDecimal("51234.12345678");

		ReferencePriceLines unselected = calculator.calculateFromPreset(entryPrice, null);

		assertThat(ExitPreset.DEFAULT).isEqualTo(ExitPreset.BALANCED);
		assertThat(unselected).isEqualTo(calculator.calculateFromPreset(entryPrice, ExitPreset.BALANCED));
	}

	@Test
	void everyPresetAppliesItsOwnRatesAsPercentNumbers() {
		BigDecimal entryPrice = new BigDecimal("1000.00000000");

		assertThat(calculator.calculateFromPreset(entryPrice, ExitPreset.CAUTIOUS))
			.isEqualTo(new ReferencePriceLines(
				new BigDecimal("980.00000000"), new BigDecimal("1030.00000000")));
		assertThat(calculator.calculateFromPreset(entryPrice, ExitPreset.RELAXED))
			.isEqualTo(new ReferencePriceLines(
				new BigDecimal("950.00000000"), new BigDecimal("1080.00000000")));
	}

	@Test
	void calculateFromPresetNormalizesEntryPriceBeforeApplyingTheRates() {
		BigDecimal rawEntryPrice = new BigDecimal("100.000000005");
		BigDecimal preRounded = rawEntryPrice.setScale(8, RoundingMode.HALF_UP);

		ReferencePriceLines lines = calculator.calculateFromPreset(rawEntryPrice, ExitPreset.BALANCED);

		assertThat(lines).isEqualTo(calculator.calculateFromPreset(preRounded, ExitPreset.BALANCED));
		assertThat(lines.referenceStopLossPrice())
			.isEqualTo(preRounded.multiply(currentMultiplier("STOP_LOSS_MULTIPLIER"))
				.setScale(8, RoundingMode.HALF_UP));
	}

	@Test
	void calculateFromPresetFailsLoudlyWhenEntryPriceIsMissing() {
		assertThatThrownBy(() -> calculator.calculateFromPreset(null, ExitPreset.BALANCED))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
