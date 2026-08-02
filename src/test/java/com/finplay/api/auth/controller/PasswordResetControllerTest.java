// 비밀번호 재설정 발송 컨트롤러의 202 응답·요청 검증·서비스 예외 매핑과 공개 경로 계약을 검증하는 @WebMvcTest 슬라이스 테스트 (ADR-0003)
package com.finplay.api.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.service.PasswordResetService;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
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

// 대상 경로가 공개 화이트리스트에 있으므로 실제 Security 체인을 태워 화이트리스트 계약까지 함께 검증한다.
@WebMvcTest(PasswordResetController.class)
@Import(SecurityConfig.class)
class PasswordResetControllerTest {

	private static final String PATH = "/api/auth/password-resets";
	private static final String VALID_BODY = "{\"email\":\"user@finplay.com\"}";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PasswordResetService passwordResetService;

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

	// 로컬파트는 64자를 넘으면 @Email 자체에 걸리므로, @Size 경계만 건드리려면 도메인 라벨(각 63자 이하)을 늘려 길이를 맞춘다.
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
}
