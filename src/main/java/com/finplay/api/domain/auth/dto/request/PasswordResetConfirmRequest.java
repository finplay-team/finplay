package com.finplay.api.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmRequest(
	@NotBlank(message = "이메일은 필수입니다.") @Size(max = 255, message = "이메일은 최대 255자까지 입력할 수 있습니다.") @Email(message = "이메일 형식이 올바르지 않습니다.")
	String email,
	@NotBlank(message = "인증번호는 필수입니다.") @Size(max = 6, message = "인증번호는 최대 6자까지 입력할 수 있습니다.") @Pattern(regexp = "\\d{6}", message = "인증번호는 숫자 6자리여야 합니다.")
	String code,
	@NotBlank(message = "새 비밀번호는 필수입니다.") @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 100자 이하로 입력해야 합니다.")
	String newPassword) {
}
