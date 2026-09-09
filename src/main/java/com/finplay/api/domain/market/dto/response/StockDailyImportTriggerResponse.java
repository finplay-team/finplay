package com.finplay.api.domain.market.dto.response;

import java.time.LocalDate;

public record StockDailyImportTriggerResponse(
	LocalDate serviceDate,
	LocalDate targetEndDate,
	long newlyCollectedCount,
	long totalArchivedCount,
	String importStatus,
	String failureReason) {
}
