package com.finplay.api.domain.education.priceruntime.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PracticePriceGeneratorV1Test {

	@Test
	void nextPriceMatchesIndependentlyRecomputedDigestForSeed12345Tick1() {
		BigDecimal previousPrice = new BigDecimal("10000.00000000");
		BigDecimal startPrice = new BigDecimal("10000.00000000");

		BigDecimal result = PracticePriceGeneratorV1.nextPrice(12345L, 1, previousPrice, startPrice);

		assertThat(result).isEqualByComparingTo("10092.29000000");
	}

	@Test
	void nextPriceMatchesIndependentlyRecomputedDigestForSeed12345Tick2ChainedFromTick1() {
		BigDecimal startPrice = new BigDecimal("10000.00000000");
		BigDecimal tick1Price = PracticePriceGeneratorV1.nextPrice(
			12345L, 1, startPrice, startPrice);

		BigDecimal result = PracticePriceGeneratorV1.nextPrice(12345L, 2, tick1Price, startPrice);

		assertThat(tick1Price).isEqualByComparingTo("10092.29000000");
		assertThat(result).isEqualByComparingTo("10019.56495826");
	}

	@Test
	void nextPriceMatchesIndependentlyRecomputedDigestForNegativeSeed() {
		BigDecimal previousPrice = new BigDecimal("50000.00000000");
		BigDecimal startPrice = new BigDecimal("50000.00000000");

		BigDecimal result = PracticePriceGeneratorV1.nextPrice(-98765L, 1, previousPrice, startPrice);

		assertThat(result).isEqualByComparingTo("49836.95000000");
	}

	@Test
	void nextPriceMatchesIndependentlyRecomputedDigestForSeedZeroAndFractionalPrice() {
		BigDecimal previousPrice = new BigDecimal("9500.12345678");
		BigDecimal startPrice = new BigDecimal("9500.12345678");

		BigDecimal result = PracticePriceGeneratorV1.nextPrice(0L, 1, previousPrice, startPrice);

		assertThat(result).isEqualByComparingTo("9454.29486122");
	}

	@Test
	void nextPriceIsDeterministicForSameSeedTickPreviousPriceAndStartPrice() {
		BigDecimal previousPrice = new BigDecimal("12345.67890000");
		BigDecimal startPrice = new BigDecimal("12000.00000000");

		BigDecimal first = PracticePriceGeneratorV1.nextPrice(777L, 42, previousPrice, startPrice);
		BigDecimal second = PracticePriceGeneratorV1.nextPrice(777L, 42, previousPrice, startPrice);

		assertThat(first).isEqualByComparingTo(second);
	}

	@Test
	void nextPriceClampsToHalfOfStartPriceWhenCandidateFallsBelowFloor() {
		BigDecimal previousPrice = new BigDecimal("3900.00000000");
		BigDecimal startPrice = new BigDecimal("10000.00000000");

		BigDecimal result = PracticePriceGeneratorV1.nextPrice(12345L, 5, previousPrice, startPrice);

		assertThat(result).isEqualByComparingTo("5000.00000000");
	}

	@Test
	void nextPriceDoesNotClampWhenCandidateIsAboveFloor() {
		BigDecimal startPrice = new BigDecimal("10000.00000000");
		BigDecimal previousPrice = new BigDecimal("5050.00000000");

		BigDecimal result = PracticePriceGeneratorV1.nextPrice(12345L, 5, previousPrice, startPrice);

		assertThat(result).isEqualByComparingTo("5065.56410000");
	}
}
