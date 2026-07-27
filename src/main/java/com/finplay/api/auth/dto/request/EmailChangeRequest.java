// 새 이메일 변경 인증번호 발송 요청 본문 — 회원 유형에 따라 currentPassword/reauthToken 중 하나만 사용한다
package com.finplay.api.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailChangeRequest(
	@NotBlank(message = "이메일은 필수입니다.") @Email(message = "이메일 형식이 올바르지 않습니다.") @Size(max = 255, message = "이메일은 최대 255자까지 입력할 수 있습니다.")
	String newEmail,
	// EMAIL 회원만 사용한다. OAuth 전용 회원은 비워 두며 서비스 계층에서 회원 유형에 따라 필수 여부를 판단한다.
	@Size(max = 100, message = "현재 비밀번호는 최대 100자까지 입력할 수 있습니다.")
	String currentPassword,
	// OAuth 전용 회원만 사용한다. EMAIL 회원은 비워 두며 서비스 계층에서 회원 유형에 따라 필수 여부를 판단한다.
	@Size(max = 255, message = "재인증 토큰은 최대 255자까지 입력할 수 있습니다.")
	String reauthToken) {
}
