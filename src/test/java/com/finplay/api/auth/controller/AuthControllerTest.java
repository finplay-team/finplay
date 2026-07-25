// 회원가입 API의 201 응답, 입력 검증, 비즈니스 오류 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.auth.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.service.AuthService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;

import tools.jackson.databind.ObjectMapper;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

	private static final String EMAIL = "user@finplay.com";
	private static final String NICKNAME = "finplayer";
	private static final String PASSWORD = "password123";
	private static final String SIGNUP_TOKEN = "signup-token";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoBean
	private AuthService authService;

	@Test
	void signupReturnsCreatedWithAllTokenFields() throws Exception {
		TokenResponse response = new TokenResponse(
			"access-token", "refresh-token", 3600L, 1_209_600L);
		when(authService.signup(EMAIL, NICKNAME, PASSWORD, SIGNUP_TOKEN)).thenReturn(response);

		mockMvc.perform(post("/api/auth/signup")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(EMAIL, NICKNAME, PASSWORD, true, SIGNUP_TOKEN)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.accessToken").value("access-token"))
			.andExpect(jsonPath("$.refreshToken").value("refresh-token"))
			.andExpect(jsonPath("$.accessTokenExpiresInSeconds").value(3600))
			.andExpect(jsonPath("$.refreshTokenExpiresInSeconds").value(1_209_600));

		verify(authService).signup(EMAIL, NICKNAME, PASSWORD, SIGNUP_TOKEN);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidRequests")
	void signupRejectsInvalidRequestWithoutCallingService(
			String scenario,
			String email,
			String nickname,
			String password,
			Boolean termsAgreed,
			String signupToken) throws Exception {
		mockMvc.perform(post("/api/auth/signup")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(email, nickname, password, termsAgreed, signupToken)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void signupRejectsOverlongVerificationTokenWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/auth/signup")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(EMAIL, NICKNAME, PASSWORD, true, "t".repeat(256))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(authService);
	}

	@Test
	void signupMapsDuplicateResourceToConflictErrorFormat() throws Exception {
		when(authService.signup(EMAIL, NICKNAME, PASSWORD, SIGNUP_TOKEN))
			.thenThrow(new BusinessException(ErrorCode.DUPLICATE_RESOURCE));

		mockMvc.perform(post("/api/auth/signup")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(EMAIL, NICKNAME, PASSWORD, true, SIGNUP_TOKEN)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.DUPLICATE_RESOURCE.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void signupMapsEmailVerificationRequiredToConflictErrorFormat() throws Exception {
		when(authService.signup(EMAIL, NICKNAME, PASSWORD, SIGNUP_TOKEN))
			.thenThrow(new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED));

		mockMvc.perform(post("/api/auth/signup")
			.contentType(MediaType.APPLICATION_JSON)
			.content(requestJson(EMAIL, NICKNAME, PASSWORD, true, SIGNUP_TOKEN)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_REQUIRED"))
			.andExpect(jsonPath("$.error.message").value(ErrorCode.EMAIL_VERIFICATION_REQUIRED.getDefaultMessage()))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	private String requestJson(
			String email,
			String nickname,
			String password,
			Boolean termsAgreed,
			String signupToken) throws Exception {
		Map<String, Object> request = new LinkedHashMap<>();
		if (email != null) {
			request.put("email", email);
		}
		if (nickname != null) {
			request.put("nickname", nickname);
		}
		if (password != null) {
			request.put("password", password);
		}
		if (termsAgreed != null) {
			request.put("termsAgreed", termsAgreed);
		}
		if (signupToken != null) {
			request.put("signupVerificationToken", signupToken);
		}
		return objectMapper.writeValueAsString(request);
	}

	private static Stream<Arguments> invalidRequests() {
		return Stream.of(
			Arguments.of("email 누락", null, NICKNAME, PASSWORD, true, SIGNUP_TOKEN),
			Arguments.of("email 형식 오류", "not-an-email", NICKNAME, PASSWORD, true, SIGNUP_TOKEN),
			Arguments.of("email 256자", "a".repeat(244) + "@example.com", NICKNAME, PASSWORD, true,
				SIGNUP_TOKEN),
			Arguments.of("nickname 누락", EMAIL, null, PASSWORD, true, SIGNUP_TOKEN),
			Arguments.of("nickname 51자", EMAIL, "n".repeat(51), PASSWORD, true, SIGNUP_TOKEN),
			Arguments.of("password 누락", EMAIL, NICKNAME, null, true, SIGNUP_TOKEN),
			Arguments.of("password 7자", EMAIL, NICKNAME, "1234567", true, SIGNUP_TOKEN),
			Arguments.of("password 101자", EMAIL, NICKNAME, "p".repeat(101), true, SIGNUP_TOKEN),
			Arguments.of("termsAgreed 누락", EMAIL, NICKNAME, PASSWORD, null, SIGNUP_TOKEN),
			Arguments.of("termsAgreed false", EMAIL, NICKNAME, PASSWORD, false, SIGNUP_TOKEN),
			Arguments.of("signupVerificationToken 누락", EMAIL, NICKNAME, PASSWORD, true, null));
	}
}
