// 투자일기 목록 커서 페이지네이션의 커서 문자열을 파싱·인코딩하는 값 객체
package com.finplay.api.domain.journal.service;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public record JournalCursor(LocalDateTime createdAt, Long tradeId) {

	private static final DateTimeFormatter CREATED_AT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

	public static JournalCursor parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null; // 커서 없음 = 첫 페이지
		}
		int separatorIndex = raw.lastIndexOf('_');
		if (separatorIndex <= 0 || separatorIndex == raw.length() - 1) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다.");
		}
		try {
			LocalDateTime createdAt = LocalDateTime.parse(raw.substring(0, separatorIndex), CREATED_AT_FORMAT);
			Long tradeId = Long.parseLong(raw.substring(separatorIndex + 1));
			return new JournalCursor(createdAt, tradeId);
		} catch (DateTimeParseException | NumberFormatException e) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다.");
		}
	}

	public static String encode(LocalDateTime createdAt, Long tradeId) {
		return createdAt.format(CREATED_AT_FORMAT) + "_" + tradeId;
	}
}
