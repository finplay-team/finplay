package com.finplay.api.domain.feedback.service;

import java.time.LocalDateTime;

public record NewsSourceDto(
	String title,
	String publisher,
	LocalDateTime publishedAt,
	boolean disclosure) {
}
