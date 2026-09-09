package com.finplay.api.domain.education.priceruntime.dto.response;

import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSessionStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PracticePriceSessionResponse(
	Long sessionId,
	Long instrumentId,
	PracticePriceSessionStatus status,
	int generatorVersion,
	BigDecimal startPrice,
	int currentTick,
	BigDecimal currentPrice,
	int tickSeconds,
	int totalTicks,
	LocalDateTime createdAt,
	LocalDateTime completedAt) {

	private static final int TICK_SECONDS = 3;
	private static final int TOTAL_TICKS = 100;

	public static PracticePriceSessionResponse from(PracticePriceSession session) {
		return new PracticePriceSessionResponse(
			session.getId(),
			session.getInstrumentId(),
			session.getStatus(),
			session.getGeneratorVersion(),
			session.getStartPrice(),
			session.getCurrentTick(),
			session.getCurrentPrice(),
			TICK_SECONDS,
			TOTAL_TICKS,
			session.getCreatedAt(),
			session.getCompletedAt());
	}
}
