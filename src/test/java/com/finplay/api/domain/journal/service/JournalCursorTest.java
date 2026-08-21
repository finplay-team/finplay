// JournalCursor의 파싱·인코딩을 검증하는 순수 단위 테스트다.
package com.finplay.api.domain.journal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class JournalCursorTest {

	@Test
	void parseReturnsCursorWithCreatedAtAndTradeId() {
		JournalCursor cursor = JournalCursor.parse("2026-07-18T10:00:00_42");

		assertThat(cursor).isNotNull();
		assertThat(cursor.createdAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 0, 0));
		assertThat(cursor.tradeId()).isEqualTo(42L);
	}

	@Test
	void parseReturnsNullWhenRawIsNull() {
		assertThat(JournalCursor.parse(null)).isNull();
	}

	@Test
	void parseReturnsNullWhenRawIsBlank() {
		assertThat(JournalCursor.parse("")).isNull();
		assertThat(JournalCursor.parse("   ")).isNull();
	}

	@Test
	void parseThrowsValidationErrorWhenSeparatorMissing() {
		assertThatThrownBy(() -> JournalCursor.parse("2026-07-18T10:00:00"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenDateTimePartIsInvalid() {
		assertThatThrownBy(() -> JournalCursor.parse("not-a-date_42"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenTradeIdPartIsInvalid() {
		assertThatThrownBy(() -> JournalCursor.parse("2026-07-18T10:00:00_not-a-number"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenTradeIdPartIsEmpty() {
		assertThatThrownBy(() -> JournalCursor.parse("2026-07-18T10:00:00_"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void encodeFormatsCreatedAtAndTradeIdWithUnderscoreSeparator() {
		LocalDateTime createdAt = LocalDateTime.of(2026, 8, 4, 9, 30, 15);

		String encoded = JournalCursor.encode(createdAt, 77L);

		assertThat(encoded).isEqualTo("2026-08-04T09:30:15_77");
	}

	@Test
	void encodeThenParseRoundTripsToOriginalCreatedAtAndTradeId() {
		LocalDateTime createdAt = LocalDateTime.of(2026, 7, 18, 10, 0, 0);

		String encoded = JournalCursor.encode(createdAt, 77L);
		JournalCursor parsed = JournalCursor.parse(encoded);

		assertThat(parsed).isNotNull();
		assertThat(parsed.createdAt()).isEqualTo(createdAt);
		assertThat(parsed.tradeId()).isEqualTo(77L);
	}
}
