package com.finplay.api.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(
	@NotBlank(message = "리프레시 토큰은 필수입니다.") @Size(max = 4096, message = "리프레시 토큰은 최대 4096자까지 입력할 수 있습니다.")
	String refreshToken) {
}
