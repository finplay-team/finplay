package com.finplay.api.domain.order.dto.request;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

public record LimitOrderUpdateRequest(
	@DecimalMin(value = "0", inclusive = false, message = "지정가는 0보다 커야 합니다.")
	BigDecimal limitPrice,
	@DecimalMin(value = "0", inclusive = false, message = "수량은 0보다 커야 합니다.")
	BigDecimal quantity) {
}
