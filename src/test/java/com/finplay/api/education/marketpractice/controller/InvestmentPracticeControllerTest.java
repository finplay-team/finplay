// 실습 진행 조회 API(GET /api/education/practice)의 인증·검증·응답 매핑을 검증하는 WebMvc 테스트다.
package com.finplay.api.education.marketpractice.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeEvidenceResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeStepResponse;
import com.finplay.api.education.marketpractice.service.InvestmentPracticeQueryService;
import com.finplay.api.market.domain.Market;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InvestmentPracticeController.class)
@Import(SecurityConfig.class)
class InvestmentPracticeControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private InvestmentPracticeQueryService investmentPracticeQueryService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void getProgressRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(get("/api/education/practice").param("market", "STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(investmentPracticeQueryService);
	}

	@Test
	void getProgressRejectsMissingMarketParam() throws Exception {
		authenticate();
		mockMvc.perform(get("/api/education/practice")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(investmentPracticeQueryService);
	}

	@Test
	void getProgressRejectsInvalidMarketValue() throws Exception {
		authenticate();
		mockMvc.perform(get("/api/education/practice")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.param("market", "NOT_A_MARKET"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(investmentPracticeQueryService);
	}

	@Test
	void getProgressReturnsServiceResponseVerbatimWhenCompleted() throws Exception {
		authenticate();
		PracticeEvidenceResponse evidence = new PracticeEvidenceResponse(
			10L, LocalDateTime.of(2026, 8, 1, 9, 0), 20L, LocalDateTime.of(2026, 8, 2, 9, 0), 30L,
			LocalDateTime.of(2026, 8, 3, 9, 0), 40L, null, null, 60L,
			LocalDateTime.of(2026, 8, 9, 9, 0), "CLOSER_TO_BOUNDARY", 70L, LocalDateTime.of(2026, 8, 10, 9, 0));
		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, "COMPLETED", false, evidence),
			new PracticeStepResponse(2, "COMPLETED", false, evidence),
			new PracticeStepResponse(3, "COMPLETED", false, evidence));
		InvestmentPracticeResponse response = new InvestmentPracticeResponse(
			"INVESTMENT_PRACTICE_V1", "COMPLETED", null, steps, LocalDateTime.of(2026, 8, 10, 9, 0));
		when(investmentPracticeQueryService.getProgress(eq(USER_ID), eq(Market.STOCK))).thenReturn(response);

		mockMvc.perform(get("/api/education/practice")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tutorialKey").value("INVESTMENT_PRACTICE_V1"))
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.currentStep").doesNotExist())
			.andExpect(jsonPath("$.completedAt").value("2026-08-10T09:00:00"))
			.andExpect(jsonPath("$.steps.length()").value(3))
			.andExpect(jsonPath("$.steps[0].step").value(1))
			.andExpect(jsonPath("$.steps[0].status").value("COMPLETED"))
			.andExpect(jsonPath("$.steps[0].locked").value(false))
			.andExpect(jsonPath("$.steps[0].evidence.favoriteId").value(10))
			.andExpect(jsonPath("$.steps[0].evidence.holdingId").value(40))
			.andExpect(jsonPath("$.steps[0].evidence.evidenceType").value("CLOSER_TO_BOUNDARY"))
			.andExpect(jsonPath("$.steps[0].evidence.reflectionId").value(70));
	}

	@Test
	void getProgressReturnsServiceResponseVerbatimWhenNotStarted() throws Exception {
		authenticate();
		PracticeEvidenceResponse emptyEvidence = PracticeEvidenceResponse.empty();
		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, "NOT_STARTED", false, emptyEvidence),
			new PracticeStepResponse(2, "NOT_STARTED", true, emptyEvidence),
			new PracticeStepResponse(3, "NOT_STARTED", true, emptyEvidence));
		InvestmentPracticeResponse response = new InvestmentPracticeResponse(
			"COIN_PRACTICE_V1", "NOT_STARTED", 1, steps, null);
		when(investmentPracticeQueryService.getProgress(eq(USER_ID), eq(Market.CRYPTO))).thenReturn(response);

		mockMvc.perform(get("/api/education/practice")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.param("market", "CRYPTO"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tutorialKey").value("COIN_PRACTICE_V1"))
			.andExpect(jsonPath("$.status").value("NOT_STARTED"))
			.andExpect(jsonPath("$.currentStep").value(1))
			.andExpect(jsonPath("$.completedAt").doesNotExist())
			.andExpect(jsonPath("$.steps[0].locked").value(false))
			.andExpect(jsonPath("$.steps[0].evidence.favoriteId").doesNotExist())
			.andExpect(jsonPath("$.steps[1].locked").value(true))
			.andExpect(jsonPath("$.steps[2].locked").value(true));
	}

	@Test
	void getProgressReturnsServiceResponseVerbatimWhenInProgressStepTwo() throws Exception {
		authenticate();
		PracticeEvidenceResponse favoriteEvidence = PracticeEvidenceResponse.favoriteOnly(
			10L, LocalDateTime.of(2026, 8, 1, 9, 0));
		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, "COMPLETED", false, favoriteEvidence),
			new PracticeStepResponse(2, "IN_PROGRESS", false, favoriteEvidence),
			new PracticeStepResponse(3, "NOT_STARTED", true, PracticeEvidenceResponse.empty()));
		InvestmentPracticeResponse response = new InvestmentPracticeResponse(
			"INVESTMENT_PRACTICE_V1", "IN_PROGRESS", 2, steps, null);
		when(investmentPracticeQueryService.getProgress(eq(USER_ID), eq(Market.STOCK))).thenReturn(response);

		mockMvc.perform(get("/api/education/practice")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.currentStep").value(2))
			.andExpect(jsonPath("$.steps[1].status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.steps[1].evidence.favoriteId").value(10))
			.andExpect(jsonPath("$.steps[1].evidence.intentionId").doesNotExist())
			.andExpect(jsonPath("$.steps[2].locked").value(true));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}
}
