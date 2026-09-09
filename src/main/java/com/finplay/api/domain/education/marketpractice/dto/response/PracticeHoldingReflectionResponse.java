package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketReflection;
import java.time.LocalDateTime;

public record PracticeHoldingReflectionResponse(
	Long reflectionId,
	Long holdingId,
	String answer,
	LocalDateTime createdAt,
	boolean rewardGranted) {

	public static PracticeHoldingReflectionResponse from(PracticeMarketReflection reflection, boolean rewardGranted) {
		return new PracticeHoldingReflectionResponse(
			reflection.getId(),
			reflection.getHolding().getId(),
			reflection.getAnswer(),
			reflection.getCreatedAt(),
			rewardGranted);
	}

	public static PracticeHoldingReflectionResponse ofRecompletion(
		Long holdingId, String answer, LocalDateTime completedAt) {
		return new PracticeHoldingReflectionResponse(null, holdingId, answer, completedAt, false);
	}
}
