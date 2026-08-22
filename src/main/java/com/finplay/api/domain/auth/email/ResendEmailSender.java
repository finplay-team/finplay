// 운영 프로필에서만 등록되어 Resend HTTP API로 인증번호 이메일을 발송하는 EmailSender 구현 (RestClient 사용)
package com.finplay.api.domain.auth.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Profile("prod")
public class ResendEmailSender implements EmailSender {

	private static final String RESEND_BASE_URL = "https://api.resend.com";
	private static final String EMAILS_PATH = "/emails";
	private static final String VERIFICATION_SUBJECT = "[FinPlay] 이메일 인증번호";
	private static final String PASSWORD_RESET_SUBJECT = "[FinPlay] 비밀번호 재설정 인증번호";

	private final RestClient restClient;
	private final String from;

	public ResendEmailSender(
		RestClient.Builder builder,
		@Value("${resend.api-key}")
		String apiKey,
		@Value("${email.from}")
		String from) {
		this.restClient = builder.baseUrl(RESEND_BASE_URL).defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
			.build();
		this.from = from;
	}

	@Override
	public void sendVerificationCode(String toEmail, String code) {
		restClient
			.post()
			.uri(EMAILS_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.body(new ResendEmailRequest(from, toEmail, VERIFICATION_SUBJECT, buildHtml(code)))
			.retrieve()
			.toBodilessEntity();
	}

	@Override
	public void sendPasswordResetCode(String toEmail, String code) {
		restClient
			.post()
			.uri(EMAILS_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.body(new ResendEmailRequest(from, toEmail, PASSWORD_RESET_SUBJECT, buildPasswordResetHtml(code)))
			.retrieve()
			.toBodilessEntity();
	}

	private String buildHtml(String code) {
		return "<p>FinPlay 이메일 인증번호는 <strong>" + code + "</strong> 입니다. 5분 안에 입력해 주세요.</p>";
	}

	// 본인이 요청하지 않은 재설정 시도를 수신자가 알아채도록 용도와 무시 안내를 본문에 함께 넣는다.
	private String buildPasswordResetHtml(String code) {
		return "<p>FinPlay <strong>비밀번호 재설정</strong> 인증번호는 <strong>" + code + "</strong> 입니다."
			+ " 5분 안에 입력해 주세요.</p>"
			+ "<p>본인이 비밀번호 재설정을 요청하지 않았다면 이 메일을 무시해 주세요."
			+ " 인증번호를 입력하지 않으면 비밀번호는 변경되지 않으며, 인증번호를 다른 사람에게 알려주지 마세요.</p>";
	}

	// Resend 발송 API 요청 본문.
	private record ResendEmailRequest(String from, String to, String subject, String html) {
	}
}
