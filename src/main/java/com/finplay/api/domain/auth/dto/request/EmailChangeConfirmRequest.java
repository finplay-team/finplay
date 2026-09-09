package com.finplay.api.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmailChangeConfirmRequest(
	@NotBlank(message = "이메일은 필수입니다.") @Size(max = 255, message = "이메일은 최대 255자까지 입력할 수 있습니다.") @Email(message = "이메일 형식이 올바르지 않습니다.")
	String newEmail,
	@NotBlank(message = "인증번호는 필수입니다.") @Size(max = 6, message = "인증번호는 최대 6자까지 입력할 수 있습니다.") @Pattern(regexp = "\\d{6}", message = "인증번호는 숫자 6자리여야 합니다.")
	String code) {
}
