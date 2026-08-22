// 실습 3단계 자유 복기 생성 요청의 holdingId·answer 검증을 정의하는 요청 DTO
package com.finplay.api.domain.education.marketpractice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PracticeHoldingReflectionCreateRequest(
	@NotNull(message = "보유종목 ID는 필수입니다.") @Positive(message = "보유종목 ID는 양수여야 합니다.")
	Long holdingId,
	@NotBlank(message = "복기 내용은 필수입니다.") @Size(max = 2000, message = "복기 내용은 2000자를 넘을 수 없습니다.")
	String answer) {
}
