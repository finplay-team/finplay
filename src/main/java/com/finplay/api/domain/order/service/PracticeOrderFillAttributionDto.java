package com.finplay.api.domain.order.service;

public record PracticeOrderFillAttributionDto(
	Long attemptId,
	Long runNumber,
	Long userId,
	Long instrumentId) {
}
