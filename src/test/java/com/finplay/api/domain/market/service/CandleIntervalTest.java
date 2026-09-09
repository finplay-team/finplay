package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class CandleIntervalTest {

	@Test
	void fromReturnsOneMinuteWhenValueIsOneMinuteToken() {
		CandleInterval interval = CandleInterval.from("1m");

		assertThat(interval).isEqualTo(CandleInterval.ONE_MINUTE);
	}

	@Test
	void fromReturnsOneDayWhenValueIsOneDayToken() {
		CandleInterval interval = CandleInterval.from("1d");

		assertThat(interval).isEqualTo(CandleInterval.ONE_DAY);
	}

	@Test
	void fromReturnsOneWeekWhenValueIsOneWeekToken() {
		CandleInterval interval = CandleInterval.from("1w");

		assertThat(interval).isEqualTo(CandleInterval.ONE_WEEK);
	}

	@Test
	void fromReturnsOneMonthWhenValueIsUppercaseOneMonthToken() {
		CandleInterval interval = CandleInterval.from("1M");

		assertThat(interval).isEqualTo(CandleInterval.ONE_MONTH);
	}

	@Test
	void fromDistinguishesUppercaseOneMonthFromLowercaseOneMinute() {
		assertThat(CandleInterval.from("1M")).isNotEqualTo(CandleInterval.from("1m"));
		assertThat(CandleInterval.from("1M")).isEqualTo(CandleInterval.ONE_MONTH);
		assertThat(CandleInterval.from("1m")).isEqualTo(CandleInterval.ONE_MINUTE);
	}

	@Test
	void fromThrowsValidationErrorWhenValueIsUnsupportedInterval() {
		assertThatThrownBy(() -> CandleInterval.from("5m"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void fromThrowsValidationErrorWhenValueIsNull() {
		assertThatThrownBy(() -> CandleInterval.from(null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void fromThrowsValidationErrorWhenValueIsBlank() {
		assertThatThrownBy(() -> CandleInterval.from(""))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void fromThrowsValidationErrorWhenDayIntervalHasUppercaseCase() {
		assertThatThrownBy(() -> CandleInterval.from("1D"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void fromThrowsValidationErrorWhenWeekIntervalHasUppercaseCase() {
		assertThatThrownBy(() -> CandleInterval.from("1W"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void fromThrowsValidationErrorWhenMonthIntervalHasLowercaseCase() {
		assertThatThrownBy(() -> CandleInterval.from("1mo"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void fromThrowsValidationErrorForOtherKnownVariants() {
		assertThatThrownBy(() -> CandleInterval.from("1MO"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
		assertThatThrownBy(() -> CandleInterval.from("1min"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
		assertThatThrownBy(() -> CandleInterval.from("1d "))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void isAggregatedReturnsFalseOnlyForOneMinuteInterval() {
		assertThat(CandleInterval.ONE_MINUTE.isAggregated()).isFalse();
		assertThat(CandleInterval.ONE_DAY.isAggregated()).isTrue();
		assertThat(CandleInterval.ONE_WEEK.isAggregated()).isTrue();
		assertThat(CandleInterval.ONE_MONTH.isAggregated()).isTrue();
	}
}
