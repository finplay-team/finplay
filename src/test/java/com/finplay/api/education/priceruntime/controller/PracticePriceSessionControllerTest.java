// 코인 가상 가격 세션 생성·조회 API의 인증·검증·응답·오류 매핑 계약을 검증하는 WebMvc 테스트다.
package com.finplay.api.education.priceruntime.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.priceruntime.domain.PracticePriceSessionStatus;
import com.finplay.api.education.priceruntime.dto.response.PracticePriceSessionResponse;
import com.finplay.api.education.priceruntime.service.PracticePriceSessionService;
import com.finplay.api.education.priceruntime.service.PracticePriceTickService;
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

@WebMvcTest(PracticePriceSessionController.class)
@Import(SecurityConfig.class)
class PracticePriceSessionControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private PracticePriceSessionService practicePriceSessionService;
	@MockitoBean
	private PracticePriceTickService practicePriceTickService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void createSessionReturnsCreatedWithExactFieldsAndWithoutSeed() throws Exception {
		authenticate();
		when(practicePriceSessionService.createSession(USER_ID, 10L)).thenReturn(sampleResponse());

		mockMvc.perform(post("/api/education/practice/price-sessions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":10}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.sessionId").value(99))
			.andExpect(jsonPath("$.instrumentId").value(10))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.generatorVersion").value(1))
			.andExpect(jsonPath("$.startPrice").value(10000.0))
			.andExpect(jsonPath("$.currentTick").value(0))
			.andExpect(jsonPath("$.currentPrice").value(10000.0))
			.andExpect(jsonPath("$.tickSeconds").value(3))
			.andExpect(jsonPath("$.totalTicks").value(100))
			.andExpect(jsonPath("$.createdAt").value("2026-08-11T10:00:00"))
			.andExpect(jsonPath("$.completedAt").doesNotExist())
			.andExpect(jsonPath("$.seed").doesNotExist());
	}

	@Test
	void createSessionRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(post("/api/education/practice/price-sessions")
			.contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":10}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(practicePriceSessionService);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidCreateBodies")
	void createSessionRejectsMissingOrNonPositiveInstrumentId(String name, String body) throws Exception {
		authenticate();
		mockMvc.perform(post("/api/education/practice/price-sessions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(practicePriceSessionService);
	}

	@Test
	void createSessionMapsMissingInstrumentToNotFound() throws Exception {
		authenticate();
		when(practicePriceSessionService.createSession(eq(USER_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(post("/api/education/practice/price-sessions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":10}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void createSessionMapsNonTradableInstrumentToConflict() throws Exception {
		authenticate();
		when(practicePriceSessionService.createSession(eq(USER_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE));

		mockMvc.perform(post("/api/education/practice/price-sessions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":10}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSTRUMENT_NOT_TRADABLE"));
	}

	@Test
	void createSessionMapsAlreadyActiveSessionToConflict() throws Exception {
		authenticate();
		when(practicePriceSessionService.createSession(eq(USER_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_PRICE_SESSION_ALREADY_ACTIVE));

		mockMvc.perform(post("/api/education/practice/price-sessions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"instrumentId\":10}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRACTICE_PRICE_SESSION_ALREADY_ACTIVE"));
	}

	@Test
	void getSessionReturnsOkWithExactFieldsAndWithoutSeed() throws Exception {
		authenticate();
		when(practicePriceSessionService.getSession(USER_ID, 99L)).thenReturn(sampleResponse());

		mockMvc.perform(get("/api/education/practice/price-sessions/99")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value(99))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.seed").doesNotExist());
	}

	@Test
	void getSessionRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(get("/api/education/practice/price-sessions/99"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(practicePriceSessionService);
	}

	@ParameterizedTest(name = "sessionId={0}")
	@MethodSource("nonPositiveSessionIds")
	void getSessionRejectsNonPositiveSessionId(String sessionId) throws Exception {
		authenticate();
		mockMvc.perform(get("/api/education/practice/price-sessions/" + sessionId)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(practicePriceSessionService);
	}

	@Test
	void getSessionMapsMissingOrOtherOwnerSessionToNotFound() throws Exception {
		authenticate();
		when(practicePriceSessionService.getSession(anyLong(), anyLong()))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(get("/api/education/practice/price-sessions/99")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void advanceTickReturnsOkWithAdvancedFields() throws Exception {
		authenticate();
		when(practicePriceTickService.advanceTick(USER_ID, 99L, 1)).thenReturn(advancedResponse());

		mockMvc.perform(post("/api/education/practice/price-sessions/99/ticks")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedTick\":1}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentTick").value(1))
			.andExpect(jsonPath("$.currentPrice").value(10092.29))
			.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void advanceTickRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(post("/api/education/practice/price-sessions/99/ticks")
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedTick\":1}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(practicePriceTickService);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidTickAdvanceBodies")
	void advanceTickRejectsMissingOrNonPositiveExpectedTick(String name, String body) throws Exception {
		authenticate();
		mockMvc.perform(post("/api/education/practice/price-sessions/99/ticks")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(practicePriceTickService);
	}

	@ParameterizedTest(name = "sessionId={0}")
	@MethodSource("nonPositiveSessionIds")
	void advanceTickRejectsNonPositiveSessionId(String sessionId) throws Exception {
		authenticate();
		mockMvc.perform(post("/api/education/practice/price-sessions/" + sessionId + "/ticks")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedTick\":1}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(practicePriceTickService);
	}

	@Test
	void advanceTickMapsMissingOrOtherOwnerSessionToNotFound() throws Exception {
		authenticate();
		when(practicePriceTickService.advanceTick(anyLong(), anyLong(), any()))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(post("/api/education/practice/price-sessions/99/ticks")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedTick\":1}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void advanceTickMapsClosedSessionToConflict() throws Exception {
		authenticate();
		when(practicePriceTickService.advanceTick(anyLong(), anyLong(), any()))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_PRICE_SESSION_CLOSED));

		mockMvc.perform(post("/api/education/practice/price-sessions/99/ticks")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedTick\":1}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRACTICE_PRICE_SESSION_CLOSED"));
	}

	@Test
	void advanceTickMapsTickConflictToConflict() throws Exception {
		authenticate();
		when(practicePriceTickService.advanceTick(anyLong(), anyLong(), any()))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_PRICE_TICK_CONFLICT));

		mockMvc.perform(post("/api/education/practice/price-sessions/99/ticks")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content("{\"expectedTick\":1}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRACTICE_PRICE_TICK_CONFLICT"));
	}

	private static Stream<Arguments> invalidTickAdvanceBodies() {
		return Stream.of(
			Arguments.of("null expectedTick", "{\"expectedTick\":null}"),
			Arguments.of("missing expectedTick", "{}"),
			Arguments.of("zero expectedTick", "{\"expectedTick\":0}"),
			Arguments.of("negative expectedTick", "{\"expectedTick\":-1}"));
	}

	private static Stream<Arguments> invalidCreateBodies() {
		return Stream.of(
			Arguments.of("null instrumentId", "{\"instrumentId\":null}"),
			Arguments.of("missing instrumentId", "{}"),
			Arguments.of("zero instrumentId", "{\"instrumentId\":0}"),
			Arguments.of("negative instrumentId", "{\"instrumentId\":-1}"));
	}

	private static Stream<Arguments> nonPositiveSessionIds() {
		return Stream.of(Arguments.of("0"), Arguments.of("-1"));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private PracticePriceSessionResponse sampleResponse() {
		return new PracticePriceSessionResponse(
			99L, 10L, PracticePriceSessionStatus.ACTIVE, 1,
			new BigDecimal("10000.00000000"), 0, new BigDecimal("10000.00000000"),
			3, 100, LocalDateTime.of(2026, 8, 11, 10, 0), null);
	}

	private PracticePriceSessionResponse advancedResponse() {
		return new PracticePriceSessionResponse(
			99L, 10L, PracticePriceSessionStatus.ACTIVE, 1,
			new BigDecimal("10000.00000000"), 1, new BigDecimal("10092.29000000"),
			3, 100, LocalDateTime.of(2026, 8, 11, 10, 0), null);
	}
}
