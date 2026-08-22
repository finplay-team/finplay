// 캔들 과거 페이지네이션 커서 문자열을 파싱·인코딩하는 값 객체
package com.finplay.api.domain.market.service;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class CandleCursor {

	// encode 결과가 sourceTime 직렬화 표기(초까지 항상 찍힘)와 정확히 같아야 하므로 toString()을 쓰지 않는다.
	private static final DateTimeFormatter ENCODE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	private CandleCursor() {}

	public static LocalDateTime parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null; // 커서 없음 = 첫 페이지
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
