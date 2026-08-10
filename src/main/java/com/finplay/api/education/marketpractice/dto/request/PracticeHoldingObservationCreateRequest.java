// 실습 3단계 가격 관찰 생성 요청의 holdingId 검증을 정의하는 요청 DTO
package com.finplay.api.education.marketpractice.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PracticeHoldingObservationCreateRequest(
	@NotNull(message = "보유종목 ID는 필수입니다.") @Positive(message = "보유종목 ID는 양수여야 합니다.")
	Long holdingId) {
}
