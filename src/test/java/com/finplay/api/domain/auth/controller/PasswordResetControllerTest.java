package com.finplay.api.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.service.AuthService;
import com.finplay.api.domain.auth.service.PasswordResetService;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PasswordResetController.class)
@Import(SecurityConfig.class)
class PasswordResetControllerTest {

	private static final String PATH = "/api/auth/password-resets";
	private static final String CONFIRM_PATH = "/api/auth/password-resets/confirm";
	private static final String VALID_BODY = "{\"email\":\"user@finplay.com\"}";
	private static final String VALID_CONFIRM_BODY = "{\"email\":\"user@finplay.com\",\"code\":\"123456\",\"newPassword\":\"newSecret123\"}";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PasswordResetService passwordResetService;

	@MockitoBean
	private AuthService authService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	@DisplayName("정상 요청이면 202로 응답하고 본문은 없으며 서비스에 이메일이 전달된다")
	void returnsAcceptedWithoutBodyOnValidRequest() throws Exception {
		mockMvc.perform(post(PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isAccepted())
			.andExpect(content().string(""));

		verify(passwordResetService).sendResetCode("user@finplay.com");
	}

	@Test
	@DisplayName("인증 헤더 없이 호출해도 401이 아니라 정상 처리된다 — 비인증 공개 경로다")
	void acceptsRequestWithoutAuthorizationHeader() throws Exception {
		mockMvc.perform(post(PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isAccepted());

		verify(passwordResetService).sendResetCode("user@finplay.com");
	}

	@Test
	@DisplayName("유효하지 않은 Bearer 토큰이 붙어도 공개 경로라 401이 아니라 정상 처리된다")
	void acceptsRequestWithInvalidBearerTokenBecausePathIsPublic() throws Exception {
		mockMvc.perform(post(PATH)
			.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isAccepted());

		verify(passwordResetService).sendResetCode("user@finplay.com");
	}

	@ParameterizedTest(name = "본문: {0}")
	@DisplayName("이메일이 누락·공백이거나 형식이 올바르지 않으면 400 VALIDATION_ERROR이며 서비스는 호출되지 않는다")
	@ValueSource(strings = {
		"{}",
		"{\"email\":null}",
		"{\"email\":\"\"}",
		"{\"email\":\"   \"}",
		"{\"email\":\"not-an-email\"}",
		"{\"email\":\"user@\"}",
		"{\"email\":\"@finplay.com\"}"
	})
	void returnsValidationErrorOnMissingOrMalformedEmail(String body) throws Exception {
		mockMvc.perform(post(PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(passwordResetService);
	}

	@Test
	@DisplayName("형식은 유효해도 256자면 @Size에 걸려 400 VALIDATION_ERROR이며 서비스는 호출되지 않는다")
	void returnsValidationErrorWhenEmailExceedsMaxLength() throws Exception {
		String tooLongEmail = emailOfLength(256);

		mockMvc.perform(post(PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + tooLongEmail + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(passwordResetService);
	}

	@Test
	@DisplayName("255자 이메일은 경계값으로 통과해 202가 된다")
	void acceptsEmailAtMaxLengthBoundary() throws Exception {
		String maxLengthEmail = emailOfLength(255);

		mockMvc.perform(post(PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + maxLengthEmail + "\"}"))
			.andExpect(status().isAccepted());

		verify(passwordResetService).sendResetCode(maxLengthEmail);
	}

	private static String emailOfLength(int totalLength) {
		String prefix = "a@";
		int domainLength = totalLength - prefix.length();
		StringBuilder domain = new StringBuilder();
		while (domain.length() < domainLength) {
			if (domain.length() > 0) {
				domain.append('.');
			}
			domain.append("a".repeat(Math.min(63, domainLength - domain.length())));
		}
		return prefix + domain;
	}

	@Test
	@DisplayName("서비스가 TOO_MANY_REQUESTS를 던지면 429 공통 포맷으로 응답한다")
	void mapsTooManyRequestsToTooManyRequestsStatus() throws Exception {
		doThrow(new BusinessException(ErrorCode.TOO_MANY_REQUESTS))
			.when(passwordResetService).sendResetCode(any());

		mockMvc.perform(post(PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.TOO_MANY_REQUESTS.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("미가입 이메일 예외는 404와 '가입되지 않은 이메일입니다.' 메시지로 매핑한다")
	void mapsNotFoundToNotFoundStatusWithServiceMessage() throws Exception {
		doThrow(new BusinessException(ErrorCode.NOT_FOUND, "가입되지 않은 이메일입니다."))
			.when(passwordResetService).sendResetCode(any());

		mockMvc.perform(post(PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").value("가입되지 않은 이메일입니다."))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("소셜 전용 회원 예외는 409 SOCIAL_ACCOUNT_ONLY 공통 포맷으로 매핑한다")
	void mapsSocialAccountOnlyToConflict() throws Exception {
		doThrow(new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ONLY))
			.when(passwordResetService).sendResetCode(any());

		mockMvc.perform(post(PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("SOCIAL_ACCOUNT_ONLY"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.SOCIAL_ACCOUNT_ONLY.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("확인: 정상 요청이면 204로 응답하고 본문은 없으며 이메일·인증번호·새 비밀번호가 그대로 전달된다")
	void confirmReturnsNoContentWithoutBodyOnValidRequest() throws Exception {
		mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_CONFIRM_BODY))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(authService).confirmPasswordReset("user@finplay.com", "123456", "newSecret123");
	}

	@Test
	@DisplayName("확인: 인증 헤더 없이 호출해도 401이 아니다 — /confirm도 공개 경로다")
	void confirmAcceptsRequestWithoutAuthorizationHeader() throws Exception {
		mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_CONFIRM_BODY))
			.andExpect(status().isNoContent());

		verify(authService).confirmPasswordReset("user@finplay.com", "123456", "newSecret123");
	}

	@Test
	@DisplayName("확인: 유효하지 않은 Bearer 토큰이 붙어도 공개 경로라 401이 아니다")
	void confirmAcceptsRequestWithInvalidBearerTokenBecausePathIsPublic() throws Exception {
		mockMvc.perform(post(CONFIRM_PATH)
			.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_CONFIRM_BODY))
			.andExpect(status().isNoContent());

		verify(authService).confirmPasswordReset("user@finplay.com", "123456", "newSecret123");
	}

	@ParameterizedTest(name = "본문: {0}")
	@DisplayName("확인: 이메일이 누락·공백이거나 형식이 올바르지 않으면 400 VALIDATION_ERROR이며 서비스는 호출되지 않는다")
	@ValueSource(strings = {
		"{\"code\":\"123456\",\"newPassword\":\"newSecret123\"}",
		"{\"email\":null,\"code\":\"123456\",\"newPassword\":\"newSecret123\"}",
		"{\"email\":\"\",\"code\":\"123456\",\"newPassword\":\"newSecret123\"}",
		"{\"email\":\"   \",\"code\":\"123456\",\"newPassword\":\"newSecret123\"}",
		"{\"email\":\"not-an-email\",\"code\":\"123456\",\"newPassword\":\"newSecret123\"}"
	})
	void confirmReturnsValidationErrorOnMissingOrMalformedEmail(String body) throws Exception {
		assertConfirmValidationError(body);
	}

	@ParameterizedTest(name = "본문: {0}")
	@DisplayName("확인: 인증번호가 누락·공백이거나 6자리 숫자가 아니면 400 VALIDATION_ERROR이며 서비스는 호출되지 않는다")
	@ValueSource(strings = {
		"{\"email\":\"user@finplay.com\",\"newPassword\":\"newSecret123\"}",
		"{\"email\":\"user@finplay.com\",\"code\":null,\"newPassword\":\"newSecret123\"}",
		"{\"email\":\"user@finplay.com\",\"code\":\"\",\"newPassword\":\"newSecret123\"}",
		"{\"email\":\"user@finplay.com\",\"code\":\"   \",\"newPassword\":\"newSecret123\"}",
		"{\"email\":\"user@finplay.com\",\"code\":\"12345\",\"newPassword\":\"newSecret123\"}",
		"{\"email\":\"user@finplay.com\",\"code\":\"1234567\",\"newPassword\":\"newSecret123\"}",
		"{\"email\":\"user@finplay.com\",\"code\":\"12345a\",\"newPassword\":\"newSecret123\"}"
	})
	void confirmReturnsValidationErrorOnMalformedCode(String body) throws Exception {
		assertConfirmValidationError(body);
	}

	@ParameterizedTest(name = "본문: {0}")
	@DisplayName("확인: 새 비밀번호가 누락·공백이거나 8자 미만·100자 초과면 400 VALIDATION_ERROR이며 서비스는 호출되지 않는다")
	@ValueSource(strings = {
		"{\"email\":\"user@finplay.com\",\"code\":\"123456\"}",
		"{\"email\":\"user@finplay.com\",\"code\":\"123456\",\"newPassword\":null}",
		"{\"email\":\"user@finplay.com\",\"code\":\"123456\",\"newPassword\":\"\"}",
		"{\"email\":\"user@finplay.com\",\"code\":\"123456\",\"newPassword\":\"       \"}",
		"{\"email\":\"user@finplay.com\",\"code\":\"123456\",\"newPassword\":\"short12\"}"
	})
	void confirmReturnsValidationErrorOnInvalidNewPassword(String body) throws Exception {
		assertConfirmValidationError(body);
	}

	@Test
	@DisplayName("확인: 새 비밀번호가 101자면 400 VALIDATION_ERROR이며 서비스는 호출되지 않는다")
	void confirmReturnsValidationErrorWhenNewPasswordExceedsMaxLength() throws Exception {
		assertConfirmValidationError(confirmBody("user@finplay.com", "123456", "a".repeat(101)));
	}

	@Test
	@DisplayName("확인: 새 비밀번호 8자·100자 경계값은 통과해 204가 된다")
	void confirmAcceptsNewPasswordAtLengthBoundaries() throws Exception {
		String shortest = "a".repeat(8);
		String longest = "a".repeat(100);

		mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody("user@finplay.com", "123456", shortest)))
			.andExpect(status().isNoContent());
		mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody("user@finplay.com", "123456", longest)))
			.andExpect(status().isNoContent());

		verify(authService).confirmPasswordReset("user@finplay.com", "123456", shortest);
		verify(authService).confirmPasswordReset("user@finplay.com", "123456", longest);
	}

	@Test
	@DisplayName("확인: 검증 실패 예외는 400 EMAIL_VERIFICATION_FAILED 공통 포맷으로 매핑한다")
	void confirmMapsVerificationFailureToBadRequest() throws Exception {
		doThrow(new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED))
			.when(authService).confirmPasswordReset(any(), any(), any());

		mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_CONFIRM_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_FAILED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.EMAIL_VERIFICATION_FAILED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("확인: 5회 초과 예외는 429 TOO_MANY_REQUESTS 공통 포맷으로 매핑한다")
	void confirmMapsAttemptLimitToTooManyRequests() throws Exception {
		doThrow(new BusinessException(ErrorCode.TOO_MANY_REQUESTS))
			.when(authService).confirmPasswordReset(any(), any(), any());

		mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_CONFIRM_BODY))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("확인: 소셜 전용 계정 예외는 409 SOCIAL_ACCOUNT_ONLY 공통 포맷으로 매핑한다")
	void confirmMapsSocialAccountOnlyToConflict() throws Exception {
		doThrow(new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ONLY))
			.when(authService).confirmPasswordReset(any(), any(), any());

		mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_CONFIRM_BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("SOCIAL_ACCOUNT_ONLY"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("확인 요청은 발송 서비스를 건드리지 않는다 — 두 엔드포인트가 서로 다른 협력 객체를 쓴다")
	void confirmDoesNotInvokeSendService() throws Exception {
		mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_CONFIRM_BODY))
			.andExpect(status().isNoContent());

		verifyNoInteractions(passwordResetService);
	}

	@Test
	@DisplayName("발송 요청은 확인 서비스를 건드리지 않는다")
	void sendDoesNotInvokeConfirmService() throws Exception {
		mockMvc.perform(post(PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isAccepted());

		verifyNoInteractions(authService);
	}

	@Test
	@DisplayName("확인: 검증 실패 응답 본문에 제출한 인증번호·새 비밀번호가 어떤 형태로도 담기지 않는다")
	void confirmErrorBodyDoesNotEchoSubmittedCodeOrPassword() throws Exception {
		String body = confirmBody("user@finplay.com", "654321", "leaked7");

		String responseBody = mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body))
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(responseBody).doesNotContain("654321");
		assertThat(responseBody).doesNotContain("leaked7");
		assertThat(responseBody).doesNotContain("passwordHash");
	}

	private void assertConfirmValidationError(String body) throws Exception {
		mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	private static String confirmBody(String email, String code, String newPassword) {
		return "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"newPassword\":\"" + newPassword + "\"}";
	}
}
