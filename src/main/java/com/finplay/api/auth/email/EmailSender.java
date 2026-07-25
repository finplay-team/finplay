// 인증번호를 대상 이메일로 발송하는 어댑터 계약 — 구현은 프로필별로 갈린다 (Fake·Resend)
package com.finplay.api.auth.email;

public interface EmailSender {

	// 대상 이메일로 인증번호를 발송한다.
	void sendVerificationCode(String toEmail, String code);
}
