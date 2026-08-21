// 인증번호를 대상 이메일로 발송하는 어댑터 계약 — 구현은 프로필별로 갈린다 (Fake·Resend)
package com.finplay.api.domain.auth.email;

public interface EmailSender {

	// 대상 이메일로 인증번호를 발송한다 (가입 인증·이메일 변경).
	void sendVerificationCode(String toEmail, String code);

	// 비밀번호 재설정 인증번호를 발송한다 — 비로그인 상태로 자격증명을 바꾸는 경로라
	// 수신자가 "누군가 내 계정의 비밀번호 재설정을 시도했다"를 알아챌 수 있게 문구를 분리한다.
	void sendPasswordResetCode(String toEmail, String code);
}
