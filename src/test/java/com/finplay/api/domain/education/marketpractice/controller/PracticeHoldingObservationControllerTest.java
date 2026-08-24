// 실습 3단계 가격 관찰 생성 API의 인증·검증·응답·오류 계약을 검증하는 WebMvc 테스트다.
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
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.domain.education.marketpractice.service.PracticeHoldingObservationService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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

@WebMvcTest(PracticeHoldingObservationController.class)
@Import(SecurityConfig.class)
class PracticeHoldingObservationControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private PracticeHoldingObservationService practiceHoldingObservationService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void createObservationReturnsCreatedWithExactFields() throws Exception {
		authenticate();
		when(practiceHoldingObservationService.createObservation(eq(USER_ID),
			any(PracticeHoldingObservationCreateRequest.class)))
			.thenReturn(new PracticeHoldingObservationResponse(55L, 10L, new BigDecimal("95.00000000"),
				LocalDateTime.of(2026, 8, 10, 10, 0), true, "STOP_LOSS", "CLOSER_TO_BOUNDARY"));

		mockMvc.perform(post("/api/education/practice/holding-observations")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.observationId").value(55))
			.andExpect(jsonPath("$.holdingId").value(10))
			.andExpect(jsonPath("$.currentPrice").value(95.0))
			.andExpect(jsonPath("$.observedAt").value("2026-08-10T10:00:00"))
			.andExpect(jsonPath("$.closerToBoundary").value(true))
			.andExpect(jsonPath("$.closerBoundary").value("STOP_LOSS"))
			.andExpect(jsonPath("$.evidenceType").value("CLOSER_TO_BOUNDARY"));
	}

	@Test
	void createObservationReturnsCreatedWithNullEvidenceFieldsWhenNeitherEvidenceIsSatisfied() throws Exception {
		authenticate();
		when(practiceHoldingObservationService.createObservation(eq(USER_ID),
			any(PracticeHoldingObservationCreateRequest.class)))
			.thenReturn(new PracticeHoldingObservationResponse(56L, 10L, new BigDecimal("100.00000000"),
				LocalDateTime.of(2026, 8, 10, 10, 5), false, null, null));

		mockMvc.perform(post("/api/education/practice/holding-observations")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.closerBoundary").doesNotExist())
			.andExpect(jsonPath("$.evidenceType").doesNotExist());
	}

	@Test
	void createObservationRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(post("/api/education/practice/holding-observations")
			.contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(practiceHoldingObservationService);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidBodies")
	void createObservationRejectsInvalidHoldingId(String name, String body) throws Exception {
		authenticate();
		mockMvc.perform(post("/api/education/practice/holding-observations")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(practiceHoldingObservationService);
	}

	@Test
	void createObservationMapsMissingOrOtherOwnerHoldingToNotFound() throws Exception {
		assertBusinessError(ErrorCode.NOT_FOUND, 404);
	}

	@Test
	void createObservationMapsChainResolutionFailureToConflict() throws Exception {
		assertBusinessError(ErrorCode.PRACTICE_EVIDENCE_MISSING, 409);
	}

	@Test
	void createObservationMapsPriceUnavailableToConflict() throws Exception {
		assertBusinessError(ErrorCode.PRICE_UNAVAILABLE, 409);
	}

	private static Stream<Arguments> invalidBodies() {
		return Stream.of(
			Arguments.of("missing holdingId", "{}"),
			Arguments.of("null holdingId", "{\"holdingId\":null}"),
			Arguments.of("zero holdingId", "{\"holdingId\":0}"),
			Arguments.of("negative holdingId", "{\"holdingId\":-1}"),
			Arguments.of("string holdingId", "{\"holdingId\":\"abc\"}"));
	}

	private void assertBusinessError(ErrorCode errorCode, int httpStatus) throws Exception {
		authenticate();
		when(practiceHoldingObservationService.createObservation(eq(USER_ID),
			any(PracticeHoldingObservationCreateRequest.class)))
			.thenThrow(new BusinessException(errorCode));
		mockMvc.perform(post("/api/education/practice/holding-observations")
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
		return "{\"holdingId\":10}";
	}
}
