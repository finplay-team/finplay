// ReferencePriceCalculator의 PRICE/PERCENT 참조 가격선 계산과 반올림 규칙을 검증하는 단위 테스트다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.education.marketpractice.domain.ExitPreset;
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

	// EXITPRESET-002 — 아무 조작도 하지 않은 사용자의 결과는 이 기능 도입 전과 같아야 한다. 비교 대상 배율을
	// 복사해 적지 않고 PracticeAttemptOrderAttributionService의 상수를 직접 읽는다. 복사하면 상수 쪽만 바뀌어도
	// 이 테스트가 초록으로 남는다. 프리셋이 그 자리를 대체하는 042 tasks 4번에서 상수가 사라지면 필드명을
	// 문자열로 찾는 이 테스트가 실행 단계에서 빨간불이 되어(컴파일이 아니다) 이 보증을 다시 보게 만든다.
	// 근사가 아니라 BigDecimal 동등성(scale까지 같음)으로 본다 — 기준선은 화면에 그대로 찍히는 숫자다.
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

	/**
	 * 기능 도입 전 하드코딩 배율. 042 4번이 {@code PracticeAttemptOrderAttributionService}의 두 상수를
	 * 프리셋으로 대체하면서 읽을 대상이 없어졌으므로 값을 여기에 적어 둔다. 이 숫자는 이제 <b>바뀔 수 있는
	 * 설정이 아니라 고정된 과거 사실</b>이다 — EXITPRESET-002가 "아무것도 고르지 않은 사용자의 결과가 이
	 * 기능 도입 전과 같아야 한다"를 요구하고, 그 비교 기준이 바로 이 두 값이다.
	 */
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
		// 프리셋의 비율은 분수가 아니라 퍼센트 수다(2%는 0.02가 아니라 2). 계산기가 다시 100으로 나누므로
		// 분수로 적으면 손절선이 진입가의 99.98%가 되어도 아무 테스트도 깨지지 않는다 — 여기서 못박는다.
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
		// 현행 코드는 체결가를 scale 8로 먼저 반올림하고 곱한다. 계산기가 그 정규화를 하지 않으면 scale 9
		// 이하 자리를 가진 체결가에서 두 경로가 갈린다 — 0.000000005는 선반올림이 0.00000001, 아니면 0이다.
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
