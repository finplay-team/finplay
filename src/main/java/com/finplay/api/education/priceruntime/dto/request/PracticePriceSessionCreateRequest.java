// 코인 가상 가격 세션 생성 요청
package com.finplay.api.education.priceruntime.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PracticePriceSessionCreateRequest(
	@NotNull(message = "종목 ID는 필수입니다.") @Positive(message = "종목 ID는 양수여야 합니다.")
	Long instrumentId) {
}
