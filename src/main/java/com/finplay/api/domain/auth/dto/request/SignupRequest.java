package com.finplay.api.domain.auth.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignupRequest(
	@NotBlank(message = "이메일은 필수입니다.") @Email(message = "이메일 형식이 올바르지 않습니다.") @Size(max = 255, message = "이메일은 최대 255자까지 입력할 수 있습니다.")
	String email,
	@NotBlank(message = "닉네임은 필수입니다.") @Size(max = 50, message = "닉네임은 최대 50자까지 입력할 수 있습니다.")
	String nickname,
	@NotBlank(message = "비밀번호는 필수입니다.") @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 100자 이하로 입력해야 합니다.")
	String password,
	@NotNull(message = "약관 동의는 필수입니다.") @AssertTrue(message = "약관에 동의해야 합니다.")
	Boolean termsAgreed,
	@NotBlank(message = "가입 인증 토큰은 필수입니다.") @Size(max = 255, message = "가입 인증 토큰은 최대 255자까지 입력할 수 있습니다.")
	String signupVerificationToken) {
}
