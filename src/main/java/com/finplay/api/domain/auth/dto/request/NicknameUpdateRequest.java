package com.finplay.api.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NicknameUpdateRequest(
	@NotBlank(message = "닉네임은 필수입니다.") @Size(max = 50, message = "닉네임은 최대 50자까지 입력할 수 있습니다.")
	String nickname,
	@Size(max = 100, message = "현재 비밀번호는 최대 100자까지 입력할 수 있습니다.")
	String currentPassword,
	@Size(max = 255, message = "재인증 토큰은 최대 255자까지 입력할 수 있습니다.")
	String reauthToken) {
}
