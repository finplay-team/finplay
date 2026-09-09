package com.finplay.api.domain.auth.email;

public interface EmailSender {

	void sendVerificationCode(String toEmail, String code);

	void sendPasswordResetCode(String toEmail, String code);
}
