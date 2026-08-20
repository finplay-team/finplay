// 코인 튜토리얼 v1 가격 생성기의 byte encoding·digest·modulo·반올림·하한 clamp를 독립 재계산값과 대조하는 단위 테스트다.
package com.finplay.api.education.priceruntime.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PracticePriceGeneratorV1Test {

	// 아래 기대값은 구현 코드를 베끼지 않고 PowerShell([System.Security.Cryptography.SHA256] +
	// [System.Numerics.BigInteger])로 seed(signed 8-byte BE) + tick(signed 4-byte BE) -> SHA-256 ->
	// 앞 8바이트 unsigned 64-bit -> units=(value mod 20001)-10000 -> rate=units/1e6 ->
	// previousPrice*(1+rate)를 scale 8 HALF_UP(AwayFromZero)으로 독립 재계산해서 뽑았다
	// (ai/specs/030-coin-practice-price-runtime/plan.md "생성기·가격 anchor" 계약).
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

	// startPrice*0.5(scale 8 HALF_UP)=5000.00000000이 하한이다. previousPrice=3900은 +1% rate를 적용해도
	// 3939.00000000으로 하한보다 작으므로 tick의 실제 rate와 무관하게 항상 clamp가 발동해야 한다.
	@Test
	void nextPriceClampsToHalfOfStartPriceWhenCandidateFallsBelowFloor() {
		BigDecimal previousPrice = new BigDecimal("3900.00000000");
		BigDecimal startPrice = new BigDecimal("10000.00000000");

		BigDecimal result = PracticePriceGeneratorV1.nextPrice(12345L, 5, previousPrice, startPrice);

		assertThat(result).isEqualByComparingTo("5000.00000000");
	}

	@Test
	void nextPriceDoesNotClampWhenCandidateIsAboveFloor() {
		// startPrice*0.5 = 5000.00000000(floor). seed=12345,tick=5의 units=3082 -> rate=0.003082이므로
		// previousPrice=5050.00000000 * 1.003082 = 5065.56410000(HALF_UP)로 floor보다 커서 clamp되지 않아야 한다.
		BigDecimal startPrice = new BigDecimal("10000.00000000");
		BigDecimal previousPrice = new BigDecimal("5050.00000000");

		BigDecimal result = PracticePriceGeneratorV1.nextPrice(12345L, 5, previousPrice, startPrice);

		assertThat(result).isEqualByComparingTo("5065.56410000");
	}
}
