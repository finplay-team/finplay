package com.finplay.api.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginExchangeRequest(
	@NotBlank(message = "교환 코드는 필수입니다.") @Size(max = 100, message = "교환 코드는 최대 100자까지 입력할 수 있습니다.")
	String code) {
}
