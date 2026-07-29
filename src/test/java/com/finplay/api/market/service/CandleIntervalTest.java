// 캔들 API의 interval 파라미터 검증(1m만 허용, 그 외는 400 VALIDATION_ERROR)을 검증하는 단위 테스트
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
	void fromThrowsValidationErrorWhenValueHasDifferentCase() {
		// "1M" 같은 대소문자 변형도 허용 값 목록("1m")과 다르므로 거부되어야 한다.
		assertThatThrownBy(() -> CandleInterval.from("1M"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}
}
