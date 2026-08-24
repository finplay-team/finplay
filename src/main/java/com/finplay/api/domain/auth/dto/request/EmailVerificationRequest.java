// 인증번호 발송 요청 본문 — 대상 이메일만 받는다
package com.finplay.api.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailVerificationRequest(
	@NotBlank(message = "이메일은 필수입니다.") @Size(max = 255, message = "이메일은 최대 255자까지 입력할 수 있습니다.") @Email(message = "이메일 형식이 올바르지 않습니다.")
	String email) {
}
