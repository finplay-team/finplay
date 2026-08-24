// 실제 발송 없이 마지막 발송 내역을 메모리에 보관하는 로컬·테스트용 EmailSender 구현
package com.finplay.api.domain.auth.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!prod")
public class FakeEmailSender implements EmailSender {

	private final List<SentEmail> sentEmails = Collections.synchronizedList(new ArrayList<>());

	@Override
	public void sendVerificationCode(String toEmail, String code) {
		sentEmails.add(new SentEmail(toEmail, code));
		log.info("[FakeEmailSender] 이메일 인증번호 발송 (실제 발송 안 함) to={} code={}", toEmail, code);
	}

	@Override
	public void sendPasswordResetCode(String toEmail, String code) {
		sentEmails.add(new SentEmail(toEmail, code));
		log.info("[FakeEmailSender] 비밀번호 재설정 인증번호 발송 (실제 발송 안 함) to={} code={}", toEmail, code);
	}

	// 테스트가 마지막으로 발송된 인증번호를 검증할 수 있게 한다.
	public SentEmail getLastSentEmail() {
		synchronized (sentEmails) {
			return sentEmails.isEmpty() ? null : sentEmails.get(sentEmails.size() - 1);
		}
	}

	public List<SentEmail> getSentEmails() {
		return List.copyOf(sentEmails);
	}

	public void clear() {
		sentEmails.clear();
	}

	public record SentEmail(String toEmail, String code) {
	}
}
