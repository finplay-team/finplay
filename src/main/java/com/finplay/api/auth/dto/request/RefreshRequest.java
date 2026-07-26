// JWT 재발급 요청의 리프레시 토큰을 검증하는 요청 DTO
package com.finplay.api.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(
	@NotBlank(message = "리프레시 토큰은 필수입니다.") @Size(max = 4096, message = "리프레시 토큰은 최대 4096자까지 입력할 수 있습니다.")
	String refreshToken) {
}
