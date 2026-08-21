// 저장된 실습 3단계 자유 복기 1건을 반환하는 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

import com.finplay.api.education.marketpractice.domain.PracticeMarketReflection;
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

	// ai/specs/040-tutorial-restart-after-completion TUTORIAL-RESTART-005/007: 재완료는
	// practice_market_reflections에 새 행을 만들지 않으므로 reflectionId가 없고, 사용자가 입력한 answer도
	// 영속되지 않는다(응답에는 evidence 검증을 통과한 이번 요청 값을 그대로 되돌려줄 뿐).
	public static PracticeHoldingReflectionResponse ofRecompletion(
		Long holdingId, String answer, LocalDateTime completedAt) {
		return new PracticeHoldingReflectionResponse(null, holdingId, answer, completedAt, false);
	}
}
