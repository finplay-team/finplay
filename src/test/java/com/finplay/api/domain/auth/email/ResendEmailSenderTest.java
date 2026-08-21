// ResendEmailSender가 Resend /emails API로 보내는 요청의 URL·헤더·본문 구성을 MockRestServiceServer로 검증하는 단위 테스트
package com.finplay.api.domain.auth.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class ResendEmailSenderTest {

	@Test
	@DisplayName("sendVerificationCode는 인증 헤더와 from·to·subject·code가 담긴 본문으로 Resend /emails를 호출한다")
	void sendVerificationCodePostsExpectedRequestToResend() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		ResendEmailSender emailSender = new ResendEmailSender(builder, "test-api-key", "no-reply@finplay.com");

		server.expect(requestTo("https://api.resend.com/emails"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-api-key"))
			.andExpect(jsonPath("$.from").value("no-reply@finplay.com"))
			.andExpect(jsonPath("$.to").value("user@example.com"))
			.andExpect(jsonPath("$.subject").value("[FinPlay] 이메일 인증번호"))
			.andExpect(jsonPath("$.html").value(containsString("123456")))
			.andRespond(withSuccess());

		emailSender.sendVerificationCode("user@example.com", "123456");

		server.verify();
	}

	@Test
	@DisplayName("sendPasswordResetCode는 재설정 전용 제목과 용도·경고 문구가 담긴 본문으로 Resend /emails를 호출한다")
	void sendPasswordResetCodePostsPasswordResetSubjectAndWarningBody() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		ResendEmailSender emailSender = new ResendEmailSender(builder, "test-api-key", "no-reply@finplay.com");

		server.expect(requestTo("https://api.resend.com/emails"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-api-key"))
			.andExpect(jsonPath("$.from").value("no-reply@finplay.com"))
			.andExpect(jsonPath("$.to").value("reset@example.com"))
			.andExpect(jsonPath("$.subject").value("[FinPlay] 비밀번호 재설정 인증번호"))
			.andExpect(jsonPath("$.html").value(containsString("654321")))
			// 수신자가 용도를 알아보고, 본인이 요청하지 않았을 때 무엇을 해야 하는지 알 수 있어야 한다.
			.andExpect(jsonPath("$.html").value(containsString("비밀번호 재설정")))
			.andExpect(jsonPath("$.html").value(containsString("요청하지 않았다면")))
			.andExpect(jsonPath("$.html").value(containsString("다른 사람에게 알려주지 마세요")))
			.andRespond(withSuccess());

		emailSender.sendPasswordResetCode("reset@example.com", "654321");

		server.verify();
	}

	@Test
	@DisplayName("가입 인증 메일과 재설정 메일은 제목·본문이 서로 달라 수신자가 용도를 구분할 수 있다")
	void verificationAndPasswordResetMailsDifferInSubjectAndBody() {
		// 두 메일이 같은 문구를 쓰면 재설정 시도를 눈치챌 수 없다 — 제목이 같아지면 이 수정이 무의미해진다.
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		ResendEmailSender emailSender = new ResendEmailSender(builder, "test-api-key", "no-reply@finplay.com");
		List<String> capturedBodies = new ArrayList<>();

		server.expect(requestTo("https://api.resend.com/emails"))
			.andExpect(captureBody(capturedBodies))
			.andRespond(withSuccess());
		server.expect(requestTo("https://api.resend.com/emails"))
			.andExpect(captureBody(capturedBodies))
			.andRespond(withSuccess());

		emailSender.sendVerificationCode("user@example.com", "123456");
		emailSender.sendPasswordResetCode("user@example.com", "123456");

		server.verify();
		assertThat(capturedBodies).hasSize(2);
		String verificationSubject = fieldOf(capturedBodies.get(0), "subject");
		String passwordResetSubject = fieldOf(capturedBodies.get(1), "subject");
		assertThat(verificationSubject).isNotBlank();
		assertThat(passwordResetSubject).isNotBlank().isNotEqualTo(verificationSubject);
		assertThat(fieldOf(capturedBodies.get(1), "html"))
			.isNotEqualTo(fieldOf(capturedBodies.get(0), "html"));
	}

	private static RequestMatcher captureBody(List<String> sink) {
		return request -> sink.add(((MockClientHttpRequest)request).getBodyAsString());
	}

	private static String fieldOf(String json, String field) {
		return new ObjectMapper().readTree(json).path(field).asString();
	}
}
