package com.finplay.api.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
	@NotBlank(message = "이메일은 필수입니다.") @Email(message = "이메일 형식이 올바르지 않습니다.") @Size(max = 255, message = "이메일은 최대 255자까지 입력할 수 있습니다.")
	String email,
	@NotBlank(message = "비밀번호는 필수입니다.") @Size(max = 100, message = "비밀번호는 최대 100자까지 입력할 수 있습니다.")
	String password) {
}
