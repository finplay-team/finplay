package com.finplay.api.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(
	@NotBlank(message = "현재 비밀번호는 필수입니다.") @Size(max = 100, message = "현재 비밀번호는 최대 100자까지 입력할 수 있습니다.")
	String currentPassword,
	@NotBlank(message = "새 비밀번호는 필수입니다.") @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 100자 이하로 입력해야 합니다.")
	String newPassword) {
}
