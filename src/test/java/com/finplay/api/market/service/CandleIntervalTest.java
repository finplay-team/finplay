// 캔들 API의 interval 파라미터 검증(1m·1d·1w·1M 4값만 허용, 대소문자 구분, 그 외는 400 VALIDATION_ERROR)을 검증하는 단위 테스트
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
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
		// spec 확정 계약(013): "1M"은 이제 유효한 월봉이다 — 003의 "1m만 허용" 계약을 대체한다.
		CandleInterval interval = CandleInterval.from("1M");

		assertThat(interval).isEqualTo(CandleInterval.ONE_MONTH);
	}

	@Test
	void fromDistinguishesUppercaseOneMonthFromLowercaseOneMinute() {
		// "1M"(월봉)과 "1m"(분봉)은 대소문자만 다르지만 서로 다른 간격으로 구분되어야 한다.
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
		// "1D"는 일봉("1d")의 대소문자 변형이므로 정규화하지 않고 거부한다(spec "공통 계약").
		assertThatThrownBy(() -> CandleInterval.from("1D"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void fromThrowsValidationErrorWhenWeekIntervalHasUppercaseCase() {
		// "1W"는 주봉("1w")의 대소문자 변형이므로 거부한다.
		assertThatThrownBy(() -> CandleInterval.from("1W"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void fromThrowsValidationErrorWhenMonthIntervalHasLowercaseCase() {
		// "1mo"는 월봉("1M")과 다른 토큰이므로 거부한다 — equalsIgnoreCase였다면 "1m"(분봉)과 충돌했을 값이다.
		assertThatThrownBy(() -> CandleInterval.from("1mo"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void fromThrowsValidationErrorForOtherKnownVariants() {
		// spec 예시에 나열된 거부 변형들: "1MO"·"1min"·"1d "(trailing space).
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
