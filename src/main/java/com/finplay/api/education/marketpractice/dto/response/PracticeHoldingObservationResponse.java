// 저장된 실습 3단계 가격 관찰 1건의 판정 결과를 반환하는 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
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
