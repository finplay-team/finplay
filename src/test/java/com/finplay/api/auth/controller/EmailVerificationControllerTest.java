// 인증번호 발송 컨트롤러의 202 응답·검증 오류·서비스 예외 매핑을 검증하는 @WebMvcTest 슬라이스 테스트 (ADR-0003)
package com.finplay.api.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.service.EmailVerificationService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EmailVerificationController.class)
class EmailVerificationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EmailVerificationService emailVerificationService;

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
}
