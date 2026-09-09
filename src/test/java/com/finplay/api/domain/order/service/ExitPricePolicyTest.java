package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.domain.education.marketpractice.service.ReferencePriceCalculator;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExitPricePolicyTest {

	private static final BigDecimal ENTRY_PRICE = new BigDecimal("100000");

	private final ExitPricePolicy exitPricePolicy = new ExitPricePolicy();
	private final ReferencePriceCalculator referencePriceCalculator = new ReferencePriceCalculator();

	@Test
	@DisplayName("PRICE 방식은 입력 절대 가격을 scale 8로 정규화해 그대로 확정한다")
	void resolvesPriceModeByNormalizingInputToScaleEight() {
		ExitPriceInputDto input = ExitPriceInputDto.ofPrice(
			ENTRY_PRICE, new BigDecimal("95000"), new BigDecimal("110000"));

		ExitPriceLinesDto lines = exitPricePolicy.resolve(input);

		assertThat(lines.stopLossPrice()).isEqualByComparingTo("95000.00000000");
		assertThat(lines.takeProfitPrice()).isEqualByComparingTo("110000.00000000");
		assertThat(lines.stopLossPrice().scale()).isEqualTo(8);
		assertThat(lines.takeProfitPrice().scale()).isEqualTo(8);
	}

	@Test
	@DisplayName("PERCENT 방식은 entryPrice × (1 ∓ rate/100)으로 계산되고 최종 결과만 scale 8 HALF_UP으로 반올림된다")
	void resolvesPercentModeUsingEntryPriceTimesOneMinusOrPlusRateOverHundred() {
		ExitPriceInputDto input = ExitPriceInputDto.ofPercent(
			ENTRY_PRICE, new BigDecimal("5"), new BigDecimal("10"));

		ExitPriceLinesDto lines = exitPricePolicy.resolve(input);

		assertThat(lines.stopLossPrice()).isEqualByComparingTo("95000.00000000");
		assertThat(lines.takeProfitPrice()).isEqualByComparingTo("110000.00000000");
	}

	@Test
	@DisplayName("소수점 4자리 rate도 중간 반올림 없이 최종 한 번만 scale 8 HALF_UP으로 반올림된다")
	void resolvesPercentModeWithFractionalRateRoundingOnlyOnce() {
		BigDecimal entryPrice = new BigDecimal("333333.33333333");
		ExitPriceInputDto input = ExitPriceInputDto.ofPercent(
			entryPrice, new BigDecimal("1.2345"), new BigDecimal("2.3456"));

		ExitPriceLinesDto lines = exitPricePolicy.resolve(input);

		BigDecimal expectedStopLoss = entryPrice
			.multiply(BigDecimal.ONE.subtract(new BigDecimal("1.2345").movePointLeft(2)))
			.setScale(8, java.math.RoundingMode.HALF_UP);
		BigDecimal expectedTakeProfit = entryPrice
			.multiply(BigDecimal.ONE.add(new BigDecimal("2.3456").movePointLeft(2)))
			.setScale(8, java.math.RoundingMode.HALF_UP);
		assertThat(lines.stopLossPrice()).isEqualByComparingTo(expectedStopLoss);
		assertThat(lines.takeProfitPrice()).isEqualByComparingTo(expectedTakeProfit);
	}

	@Test
	@DisplayName("아주 작은 rate가 반올림되어 stopLossPrice == entryPrice가 되는 경계는 409로 거부된다")
	void rejectsWhenTinyRateRoundsStopLossPriceToEqualEntryPrice() {
		BigDecimal entryPrice = new BigDecimal("0.001");
		ExitPriceInputDto input = ExitPriceInputDto.ofPercent(
			entryPrice, new BigDecimal("0.0001"), new BigDecimal("10"));

		assertThatThrownBy(() -> exitPricePolicy.resolve(input))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.EXIT_PLAN_INVALID_PRICE_RANGE));
	}

	@Test
	@DisplayName("stopLossPrice가 0 이하면 409로 거부된다")
	void rejectsWhenStopLossPriceIsZeroOrNegative() {
		ExitPriceInputDto input = ExitPriceInputDto.ofPrice(ENTRY_PRICE, BigDecimal.ZERO, new BigDecimal("110000"));

		assertThatThrownBy(() -> exitPricePolicy.resolve(input))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.EXIT_PLAN_INVALID_PRICE_RANGE));
	}

	@Test
	@DisplayName("stopLossPrice >= entryPrice이면 409로 거부된다")
	void rejectsWhenStopLossPriceIsNotBelowEntryPrice() {
		ExitPriceInputDto input = ExitPriceInputDto.ofPrice(ENTRY_PRICE, ENTRY_PRICE, new BigDecimal("110000"));

		assertThatThrownBy(() -> exitPricePolicy.resolve(input))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.EXIT_PLAN_INVALID_PRICE_RANGE));
	}

	@Test
	@DisplayName("takeProfitPrice <= entryPrice이면 409로 거부된다")
	void rejectsWhenTakeProfitPriceIsNotAboveEntryPrice() {
		ExitPriceInputDto input = ExitPriceInputDto.ofPrice(ENTRY_PRICE, new BigDecimal("95000"), ENTRY_PRICE);

		assertThatThrownBy(() -> exitPricePolicy.resolve(input))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.EXIT_PLAN_INVALID_PRICE_RANGE));
	}

	@Test
	@DisplayName("정수부 10자리를 초과하는 stopLossPrice는 DECIMAL(18,8) 상한 위반으로 409 거부된다")
	void rejectsWhenStopLossPriceIntegerPartExceedsTenDigits() {
		BigDecimal entryPrice = new BigDecimal("20000000000");
		ExitPriceInputDto input = ExitPriceInputDto.ofPrice(
			entryPrice, new BigDecimal("10000000000"), new BigDecimal("30000000000"));

		assertThatThrownBy(() -> exitPricePolicy.resolve(input))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.EXIT_PLAN_INVALID_PRICE_RANGE));
	}

	@Test
	@DisplayName("정수부 정확히 10자리는 DECIMAL(18,8) 상한을 넘지 않아 통과한다")
	void allowsStopLossPriceWithExactlyTenIntegerDigits() {
		BigDecimal entryPrice = new BigDecimal("9999999999.5");
		ExitPriceInputDto input = ExitPriceInputDto.ofPrice(
			entryPrice, new BigDecimal("9999999999"), new BigDecimal("9999999999.9"));

		ExitPriceLinesDto lines = exitPricePolicy.resolve(input);

		assertThat(lines.stopLossPrice()).isEqualByComparingTo("9999999999.00000000");
		assertThat(lines.takeProfitPrice()).isEqualByComparingTo("9999999999.90000000");
	}

	@Test
	@DisplayName("범위 위반과 정밀도 위반 모두 같은 EXIT_PLAN_INVALID_PRICE_RANGE(409)로 수렴한다")
	void rangeAndPrecisionViolationsBothConvergeOnSameErrorCode() {
		ExitPriceInputDto rangeViolation = ExitPriceInputDto.ofPrice(ENTRY_PRICE, ENTRY_PRICE,
			new BigDecimal("110000"));
		ExitPriceInputDto precisionViolation = ExitPriceInputDto.ofPrice(
			new BigDecimal("20000000000"), new BigDecimal("10000000000"), new BigDecimal("30000000000"));

		ErrorCode rangeErrorCode = catchErrorCode(rangeViolation);
		ErrorCode precisionErrorCode = catchErrorCode(precisionViolation);

		assertThat(rangeErrorCode).isEqualTo(ErrorCode.EXIT_PLAN_INVALID_PRICE_RANGE);
		assertThat(precisionErrorCode).isEqualTo(ErrorCode.EXIT_PLAN_INVALID_PRICE_RANGE);
	}

	@Test
	@DisplayName("ExitPricePolicy의 PERCENT 계산은 ReferencePriceCalculator(026)의 같은 019 공식과 같은 입력에서 같은 값을 낸다")
	void percentCalculationMatchesReferencePriceCalculatorForSameInput() {
		BigDecimal entryPrice = new BigDecimal("123456.78912345");
		BigDecimal stopLossRate = new BigDecimal("3.3333");
		BigDecimal takeProfitRate = new BigDecimal("7.7777");

		ExitPriceLinesDto engineLines = exitPricePolicy.resolve(
			ExitPriceInputDto.ofPercent(entryPrice, stopLossRate, takeProfitRate));
		var referenceLines = referencePriceCalculator
			.calculateFromPercent(entryPrice, stopLossRate, takeProfitRate)
			.orElseThrow();

		assertThat(engineLines.stopLossPrice()).isEqualByComparingTo(referenceLines.referenceStopLossPrice());
		assertThat(engineLines.takeProfitPrice()).isEqualByComparingTo(referenceLines.referenceTakeProfitPrice());
	}

	private ErrorCode catchErrorCode(ExitPriceInputDto input) {
		try {
			exitPricePolicy.resolve(input);
			throw new AssertionError("예외가 발생해야 한다");
		} catch (BusinessException ex) {
			return ex.getErrorCode();
		}
	}
}
