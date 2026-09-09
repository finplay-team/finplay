package com.finplay.api.domain.market.dto.response;

import java.time.LocalDate;

public record StockReplayImportTriggerResponse(
	LocalDate serviceDate,
	LocalDate tradingDate,
	long collectedKisCandleCount,
	String preparationStatus,
	String failureReason,
	String marketStatus) {
}
