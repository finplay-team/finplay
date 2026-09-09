package com.finplay.api.domain.market.service;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class CandleCursor {

	private static final DateTimeFormatter ENCODE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	private CandleCursor() {}

	public static LocalDateTime parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		} catch (DateTimeParseException e) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다.");
		}
	}

	public static String encode(LocalDateTime sourceTime) {
		return sourceTime.format(ENCODE_FORMAT);
	}
}
