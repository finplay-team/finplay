// 코인 가상 가격 세션의 next-tick 진행 요청
package com.finplay.api.domain.education.priceruntime.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PracticePriceTickAdvanceRequest(
	@NotNull(message = "expectedTick은 필수입니다.") @Positive(message = "expectedTick은 양수여야 합니다.")
	Integer expectedTick) {
}
