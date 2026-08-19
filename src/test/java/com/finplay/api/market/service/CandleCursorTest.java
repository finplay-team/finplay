// CandleCursor의 파싱·인코딩을 검증하는 순수 단위 테스트다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CandleCursorTest {

	@Test
	void parseReturnsLocalDateTimeForValidIsoString() {
		LocalDateTime parsed = CandleCursor.parse("2026-08-19T09:00:00");

		assertThat(parsed).isEqualTo(LocalDateTime.of(2026, 8, 19, 9, 0, 0));
	}

	@Test
	void parseAcceptsSecondsOmittedForm() {
		LocalDateTime parsed = CandleCursor.parse("2026-08-19T09:00");

		assertThat(parsed).isEqualTo(LocalDateTime.of(2026, 8, 19, 9, 0, 0));
	}

	@Test
	void parseReturnsNullWhenRawIsBlank() {
		assertThat(CandleCursor.parse(null)).isNull();
		assertThat(CandleCursor.parse("")).isNull();
		assertThat(CandleCursor.parse("   ")).isNull();
	}

	@Test
	void parseThrowsValidationErrorWhenRawIsDateOnly() {
		assertThatThrownBy(() -> CandleCursor.parse("2026-08-19"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenRawIsGarbage() {
		assertThatThrownBy(() -> CandleCursor.parse("not-a-date"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenRawHasTimezoneOffset() {
		assertThatThrownBy(() -> CandleCursor.parse("2026-08-19T09:00:00+09:00"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenRawHasUtcZoneSuffix() {
		assertThatThrownBy(() -> CandleCursor.parse("2026-08-19T09:00:00Z"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenMonthIsOutOfRange() {
		assertThatThrownBy(() -> CandleCursor.parse("2026-13-19T09:00:00"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenDayOfMonthIsOutOfRange() {
		assertThatThrownBy(() -> CandleCursor.parse("2026-08-32T09:00:00"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenHourIsOutOfRange() {
		assertThatThrownBy(() -> CandleCursor.parse("2026-08-19T25:00:00"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void encodeAlwaysPrintsSecondsEvenWhenZero() {
		String encoded = CandleCursor.encode(LocalDateTime.of(2026, 7, 22, 9, 0, 0));

		assertThat(encoded).isEqualTo("2026-07-22T09:00:00");
	}

	@Test
	void encodePreservesNonZeroSeconds() {
		String encoded = CandleCursor.encode(LocalDateTime.of(2026, 7, 22, 9, 0, 30));

		assertThat(encoded).isEqualTo("2026-07-22T09:00:30");
	}

	@Test
	void encodeDropsNanosecondsAndKeepsSecondsOnly() {
		String encoded = CandleCursor.encode(LocalDateTime.of(2026, 7, 22, 9, 0, 30, 500_000_000));

		assertThat(encoded).isEqualTo("2026-07-22T09:00:30");
	}
}
