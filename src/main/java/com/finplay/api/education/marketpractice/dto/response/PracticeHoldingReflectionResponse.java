// 저장된 실습 3단계 자유 복기 1건을 반환하는 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

import com.finplay.api.education.marketpractice.domain.PracticeMarketReflection;
import java.time.LocalDateTime;

public record PracticeHoldingReflectionResponse(
	Long reflectionId,
	Long holdingId,
	String prompt,
	String answer,
	LocalDateTime createdAt) {

	public static final String PROMPT = "지금 팔고 싶나요? 그렇다면 왜 그런가요? 계획한 손절·익절 라인과 비교해 적어보세요.";

	public static PracticeHoldingReflectionResponse from(PracticeMarketReflection reflection) {
		return new PracticeHoldingReflectionResponse(
			reflection.getId(),
			reflection.getHolding().getId(),
			PROMPT,
			reflection.getAnswer(),
			reflection.getCreatedAt());
	}
}
