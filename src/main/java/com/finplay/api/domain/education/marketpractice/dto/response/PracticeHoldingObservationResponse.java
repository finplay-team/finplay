package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PracticeHoldingObservationResponse(
	Long observationId,
	Long holdingId,
	BigDecimal currentPrice,
	LocalDateTime observedAt,
	Boolean closerToBoundary,
	String closerBoundary,
	String evidenceType) {

	public static PracticeHoldingObservationResponse from(PracticeMarketObservation observation) {
		return new PracticeHoldingObservationResponse(
			observation.getId(),
			observation.getHolding().getId(),
			observation.getCurrentPrice(),
			observation.getObservedAt(),
			observation.getCloserToBoundary(),
			observation.getCloserBoundary() == null ? null : observation.getCloserBoundary().name(),
			observation.getEvidenceType() == null ? null : observation.getEvidenceType().name());
	}
}
