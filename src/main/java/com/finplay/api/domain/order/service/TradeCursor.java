// 체결 내역 커서 페이지네이션의 커서 문자열을 파싱·인코딩하는 값 객체
package com.finplay.api.domain.order.service;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.order.entity.Trade;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public record TradeCursor(LocalDateTime executedAt, Long id) {

	private static final DateTimeFormatter EXECUTED_AT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

	public static TradeCursor parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null; // 커서 없음 = 첫 페이지
		}
		int separatorIndex = raw.lastIndexOf('_');
		if (separatorIndex <= 0 || separatorIndex == raw.length() - 1) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다.");
		}
		try {
			LocalDateTime executedAt = LocalDateTime.parse(raw.substring(0, separatorIndex), EXECUTED_AT_FORMAT);
			Long id = Long.parseLong(raw.substring(separatorIndex + 1));
			return new TradeCursor(executedAt, id);
		} catch (DateTimeParseException | NumberFormatException e) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다.");
		}
	}

	public static String encode(Trade lastTrade) {
		return lastTrade.getExecutedAt().format(EXECUTED_AT_FORMAT) + "_" + lastTrade.getId();
	}
}
