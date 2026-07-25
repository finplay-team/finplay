// ResendEmailSender가 Resend /emails API로 보내는 요청의 URL·헤더·본문 구성을 MockRestServiceServer로 검증하는 단위 테스트
package com.finplay.api.auth.email;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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
}
