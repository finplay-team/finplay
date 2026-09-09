package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public record OrderCursor(LocalDateTime requestedAt, Long id) {

	private static final DateTimeFormatter REQUESTED_AT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

	public static OrderCursor parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		int separatorIndex = raw.lastIndexOf('_');
		if (separatorIndex <= 0 || separatorIndex == raw.length() - 1) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다.");
		}
		try {
			LocalDateTime requestedAt = LocalDateTime.parse(raw.substring(0, separatorIndex), REQUESTED_AT_FORMAT);
			Long id = Long.parseLong(raw.substring(separatorIndex + 1));
			return new OrderCursor(requestedAt, id);
		} catch (DateTimeParseException | NumberFormatException e) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다.");
		}
	}

	public static String encode(Order lastOrder) {
		return lastOrder.getRequestedAt().format(REQUESTED_AT_FORMAT) + "_" + lastOrder.getId();
	}
}
