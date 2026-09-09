package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import java.math.BigDecimal;
import java.time.LocalTime;

public record PriceMoveDetectionDto(
	PriceMoveEventType eventType,
	LocalTime windowStart,
	LocalTime windowEnd,
	BigDecimal changeRate,
	BigDecimal detectionScore) {
}
