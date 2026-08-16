// 튜토리얼 attempt의 현재 실행 세대에 선택할 샘플 종목 ID를 받는 요청 DTO
package com.finplay.api.education.marketpractice.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PracticeAttemptInstrumentUpdateRequest(
	@NotNull(message = "종목 ID는 필수입니다.") @Positive(message = "종목 ID는 양수여야 합니다.")
	Long instrumentId) {
}
