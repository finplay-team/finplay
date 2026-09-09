package com.finplay.api.domain.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.dto.response.SignupTokenResponse;
import com.finplay.api.domain.auth.service.EmailVerificationService;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EmailVerificationController.class)
@Import(SecurityConfig.class)
class EmailVerificationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EmailVerificationService emailVerificationService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	@DisplayName("정상 요청이면 202로 응답하고 본문은 없으며 서비스에 이메일이 전달된다")
	void returnsAcceptedWithoutBodyOnValidRequest() throws Exception {
		mockMvc.perform(post("/api/auth/email-verifications")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"user@finplay.com\"}"))
			.andExpect(status().isAccepted())
			.andExpect(content().string(""));

		verify(emailVerificationService).sendVerificationCode("user@finplay.com");
	}

	@Test
	@DisplayName("이메일 형식이 올바르지 않으면 400 VALIDATION_ERROR 공통 포맷으로 응답한다")
	void returnsValidationErrorOnMalformedEmail() throws Exception {
		mockMvc.perform(post("/api/auth/email-verifications")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"not-an-email\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(emailVerificationService);
	}

	@Test
	@DisplayName("이메일이 누락되면 400 VALIDATION_ERROR 공통 포맷으로 응답한다")
	void returnsValidationErrorOnMissingEmail() throws Exception {
		mockMvc.perform(post("/api/auth/email-verifications")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(emailVerificationService);
	}

	@Test
	@DisplayName("서비스가 DUPLICATE_RESOURCE를 던지면 409 공통 포맷으로 응답한다")
	void mapsDuplicateResourceToConflict() throws Exception {
		doThrow(new BusinessException(ErrorCode.DUPLICATE_RESOURCE))
			.when(emailVerificationService).sendVerificationCode(any());

		mockMvc.perform(post("/api/auth/email-verifications")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"user@finplay.com\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("서비스가 TOO_MANY_REQUESTS를 던지면 429 공통 포맷으로 응답한다")
	void mapsTooManyRequestsToTooManyRequestsStatus() throws Exception {
		doThrow(new BusinessException(ErrorCode.TOO_MANY_REQUESTS))
			.when(emailVerificationService).sendVerificationCode(any());

		mockMvc.perform(post("/api/auth/email-verifications")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"user@finplay.com\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("유효한 인증번호 확인 요청은 200과 가입 검증 토큰 응답을 반환한다")
	void returnsSignupVerificationTokenOnValidConfirmRequest() throws Exception {
		when(emailVerificationService.confirmVerificationCode("user@finplay.com", "123456"))
			.thenReturn(new SignupTokenResponse("signup-verification-token", 1800L));

		mockMvc.perform(post("/api/auth/email-verifications/confirm")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"user@finplay.com\",\"code\":\"123456\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.signupVerificationToken").value("signup-verification-token"))
			.andExpect(jsonPath("$.expiresInSeconds").value(1800));

		verify(emailVerificationService).confirmVerificationCode("user@finplay.com", "123456");
	}

	@Test
	@DisplayName("인증번호 확인 요청에 이메일이 없으면 400 VALIDATION_ERROR를 반환한다")
	void returnsValidationErrorWhenConfirmEmailIsMissing() throws Exception {
		mockMvc.perform(post("/api/auth/email-verifications/confirm")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"123456\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(emailVerificationService);
	}

	@Test
	@DisplayName("인증번호 확인 요청의 이메일 형식이 잘못되면 400 VALIDATION_ERROR를 반환한다")
	void returnsValidationErrorWhenConfirmEmailIsMalformed() throws Exception {
		mockMvc.perform(post("/api/auth/email-verifications/confirm")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"not-an-email\",\"code\":\"123456\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(emailVerificationService);
	}

	@Test
	@DisplayName("인증번호 확인 요청에 코드가 없으면 400 VALIDATION_ERROR를 반환한다")
	void returnsValidationErrorWhenConfirmCodeIsMissing() throws Exception {
		mockMvc.perform(post("/api/auth/email-verifications/confirm")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"user@finplay.com\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(emailVerificationService);
	}

	@Test
	@DisplayName("인증번호 확인 요청의 코드가 숫자 여섯 자리가 아니면 400 VALIDATION_ERROR를 반환한다")
	void returnsValidationErrorWhenConfirmCodeIsNotSixDigits() throws Exception {
		mockMvc.perform(post("/api/auth/email-verifications/confirm")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"user@finplay.com\",\"code\":\"12ab5\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(emailVerificationService);
	}

	@Test
	@DisplayName("인증번호 확인 실패 예외는 400 공통 오류 응답으로 매핑한다")
	void mapsEmailVerificationFailureToBadRequest() throws Exception {
		doThrow(new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED))
			.when(emailVerificationService).confirmVerificationCode(any(), any());

		mockMvc.perform(post("/api/auth/email-verifications/confirm")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"user@finplay.com\",\"code\":\"123456\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_FAILED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("인증번호 확인 요청 제한 예외는 429 공통 오류 응답으로 매핑한다")
	void mapsConfirmRateLimitToTooManyRequestsStatus() throws Exception {
		doThrow(new BusinessException(ErrorCode.TOO_MANY_REQUESTS))
			.when(emailVerificationService).confirmVerificationCode(any(), any());

		mockMvc.perform(post("/api/auth/email-verifications/confirm")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"user@finplay.com\",\"code\":\"123456\"}"))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error.code").value("TOO_MANY_REQUESTS"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}
}
