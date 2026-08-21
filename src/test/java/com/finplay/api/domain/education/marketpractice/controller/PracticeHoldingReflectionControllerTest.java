// 실습 3단계 자유 복기 생성 API의 인증·검증·응답·오류 계약을 검증하는 WebMvc 테스트다.
package com.finplay.api.domain.education.marketpractice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.domain.education.marketpractice.service.PracticeHoldingReflectionService;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PracticeHoldingReflectionController.class)
@Import(SecurityConfig.class)
class PracticeHoldingReflectionControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private PracticeHoldingReflectionService practiceHoldingReflectionService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void createReflectionReturnsCreatedWithExactFields() throws Exception {
		authenticate();
		when(practiceHoldingReflectionService.createReflection(eq(USER_ID),
			any(PracticeHoldingReflectionCreateRequest.class)))
			.thenReturn(new PracticeHoldingReflectionResponse(
				30L, 10L, PracticeHoldingReflectionResponse.PROMPT, "손절 라인에 가까워서 팔지 않기로 했다.",
				LocalDateTime.of(2026, 8, 10, 10, 0), true));

		mockMvc.perform(post("/api/education/practice/holding-reflections")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.reflectionId").value(30))
			.andExpect(jsonPath("$.holdingId").value(10))
			.andExpect(jsonPath("$.prompt").value(PracticeHoldingReflectionResponse.PROMPT))
			.andExpect(jsonPath("$.answer").value("손절 라인에 가까워서 팔지 않기로 했다."))
			.andExpect(jsonPath("$.createdAt").value("2026-08-10T10:00:00"))
			.andExpect(jsonPath("$.rewardGranted").value(true));
	}

	@Test
	void createReflectionRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(post("/api/education/practice/holding-reflections")
			.contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(practiceHoldingReflectionService);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidBodies")
	void createReflectionRejectsInvalidBody(String name, String body) throws Exception {
		authenticate();
		mockMvc.perform(post("/api/education/practice/holding-reflections")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(practiceHoldingReflectionService);
	}

	@Test
	void createReflectionMapsMissingOrOtherOwnerHoldingToNotFound() throws Exception {
		assertBusinessError(ErrorCode.NOT_FOUND, 404);
	}

	@Test
	void createReflectionMapsEvidenceMissingToConflict() throws Exception {
		assertBusinessError(ErrorCode.PRACTICE_EVIDENCE_MISSING, 409);
	}

	@Test
	void createReflectionMapsAlreadyCompletedToConflict() throws Exception {
		assertBusinessError(ErrorCode.PRACTICE_ALREADY_COMPLETED, 409);
	}

	private static Stream<Arguments> invalidBodies() {
		String maxAnswer = "a".repeat(2000);
		String tooLongAnswer = "a".repeat(2001);
		return Stream.of(
			Arguments.of("missing holdingId", "{\"answer\":\"" + maxAnswer + "\"}"),
			Arguments.of("null holdingId", "{\"holdingId\":null,\"answer\":\"" + maxAnswer + "\"}"),
			Arguments.of("zero holdingId", "{\"holdingId\":0,\"answer\":\"" + maxAnswer + "\"}"),
			Arguments.of("negative holdingId", "{\"holdingId\":-1,\"answer\":\"" + maxAnswer + "\"}"),
			Arguments.of("missing answer", "{\"holdingId\":10}"),
			Arguments.of("blank answer", "{\"holdingId\":10,\"answer\":\"   \"}"),
			Arguments.of("answer over 2000 chars", "{\"holdingId\":10,\"answer\":\"" + tooLongAnswer + "\"}"));
	}

	private void assertBusinessError(ErrorCode errorCode, int httpStatus) throws Exception {
		authenticate();
		when(practiceHoldingReflectionService.createReflection(eq(USER_ID),
			any(PracticeHoldingReflectionCreateRequest.class)))
			.thenThrow(new BusinessException(errorCode));
		mockMvc.perform(post("/api/education/practice/holding-reflections")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().is(httpStatus))
			.andExpect(jsonPath("$.error.code").value(errorCode.name()));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private static String validBody() {
		return "{\"holdingId\":10,\"answer\":\"손절 라인에 가까워서 팔지 않기로 했다.\"}";
	}
}
