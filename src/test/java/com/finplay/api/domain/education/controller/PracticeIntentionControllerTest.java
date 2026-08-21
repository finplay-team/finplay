// 실습 의도 생성 API의 인증·검증·응답·오류 계약을 검증하는 WebMvc 테스트다.
package com.finplay.api.domain.education.controller;

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
import com.finplay.api.domain.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.domain.education.dto.response.PracticeIntentionResponse;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import java.math.BigDecimal;
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

@WebMvcTest(PracticeIntentionController.class)
@Import(SecurityConfig.class)
class PracticeIntentionControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;
	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private PracticeIntentionService practiceIntentionService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void createIntentionReturnsCreatedWithExactFields() throws Exception {
		authenticate();
		when(practiceIntentionService.createIntention(org.mockito.ArgumentMatchers.eq(USER_ID),
			org.mockito.ArgumentMatchers.any(PracticeIntentionCreateRequest.class)))
			.thenReturn(new PracticeIntentionResponse(99L, 10L, new BigDecimal("2.50000000"),
				new BigDecimal("90.00000000"), new BigDecimal("120.00000000"),
				LocalDateTime.of(2026, 8, 4, 10, 0)));

		mockMvc.perform(post("/api/education/practice/intentions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.intentionId").value(99))
			.andExpect(jsonPath("$.instrumentId").value(10))
			.andExpect(jsonPath("$.quantity").value(2.5))
			.andExpect(jsonPath("$.stopLoss").value(90.0))
			.andExpect(jsonPath("$.takeProfit").value(120.0))
			.andExpect(jsonPath("$.createdAt").value("2026-08-04T10:00:00"));
	}

	@Test
	void createIntentionRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(post("/api/education/practice/intentions")
			.contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(practiceIntentionService);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidBodies")
	void createIntentionRejectsInvalidDecimalAndRequiredValues(String name, String body) throws Exception {
		authenticate();
		mockMvc.perform(post("/api/education/practice/intentions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(practiceIntentionService);
	}

	@Test
	void createIntentionMapsMissingInstrumentToNotFound() throws Exception {
		assertBusinessError(ErrorCode.NOT_FOUND, 404);
	}

	@Test
	void createIntentionMapsLockedStepToConflict() throws Exception {
		assertBusinessError(ErrorCode.PRACTICE_STEP_LOCKED, 409);
	}

	@Test
	void createIntentionMapsCompletedPracticeToConflict() throws Exception {
		assertBusinessError(ErrorCode.PRACTICE_ALREADY_COMPLETED, 409);
	}

	private static Stream<Arguments> invalidBodies() {
		return Stream.of(
			Arguments.of("null instrument", validBody().replace("10", "null")),
			Arguments.of("zero instrument", validBody().replace("10", "0")),
			Arguments.of("negative quantity", validBody().replace("2.5", "-2.5")),
			Arguments.of("zero stop loss", validBody().replace("90", "0")),
			Arguments.of("negative take profit", validBody().replace("120", "-120")),
			Arguments.of("quantity precision overflow", validBody().replace("2.5", "12345678901234567890123.1")),
			Arguments.of("quantity scale overflow", validBody().replace("2.5", "1.123456789")),
			Arguments.of("price precision overflow", validBody().replace("90", "12345678901.1")),
			Arguments.of("price scale overflow", validBody().replace("120", "120.123456789")),
			Arguments.of("null decimals",
				"{\"instrumentId\":10,\"quantity\":null,\"stopLoss\":null,\"takeProfit\":null}"));
	}

	private void assertBusinessError(ErrorCode errorCode, int httpStatus) throws Exception {
		authenticate();
		when(practiceIntentionService.createIntention(org.mockito.ArgumentMatchers.eq(USER_ID),
			org.mockito.ArgumentMatchers.any(PracticeIntentionCreateRequest.class)))
			.thenThrow(new BusinessException(errorCode));
		mockMvc.perform(post("/api/education/practice/intentions")
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
		return "{\"instrumentId\":10,\"quantity\":2.5,\"stopLoss\":90,\"takeProfit\":120}";
	}
}
