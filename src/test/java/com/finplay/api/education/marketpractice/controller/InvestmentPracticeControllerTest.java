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
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeEvidenceResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeRiskSnapshotResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeStepResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeTradeResultResponse;
import com.finplay.api.education.marketpractice.service.InvestmentPracticeQueryService;
import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
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
		// 이슈 #421: 매도가 끝난 evidence는 tradeResult 다섯 필드가 모두 채워진 채로 직렬화돼야 한다.
		PracticeTradeResultResponse tradeResult = new PracticeTradeResultResponse(
			new BigDecimal("10000.00000000"), new BigDecimal("10500.00000000"), 4_985L, new BigDecimal("0.0500"),
			"ABOVE_TAKE_PROFIT");
		PracticeEvidenceResponse evidence = new PracticeEvidenceResponse(
			10L, LocalDateTime.of(2026, 8, 1, 9, 0), 20L, LocalDateTime.of(2026, 8, 2, 9, 0), 30L,
			LocalDateTime.of(2026, 8, 3, 9, 0), 40L, null, null, 60L,
			LocalDateTime.of(2026, 8, 9, 9, 0), "CLOSER_TO_BOUNDARY", 70L, LocalDateTime.of(2026, 8, 10, 9, 0),
			null, null, null, new BigDecimal("10"), new BigDecimal("4"), new BigDecimal("6"), tradeResult);
		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, "COMPLETED", false, evidence),
			new PracticeStepResponse(2, "COMPLETED", false, evidence),
			new PracticeStepResponse(3, "COMPLETED", false, evidence));
		PracticeAttemptResponse attempt = new PracticeAttemptResponse(
			99L, "STOCK", 2L, "REPLAY", "COMPLETED", 100L,
			LocalDateTime.of(2026, 8, 3, 9, 0), LocalDate.of(2026, 8, 3),
			new PracticeRiskSnapshotResponse(
				new BigDecimal("100.00000000"), new BigDecimal("97.00000000"),
				new BigDecimal("105.00000000"), 30L, LocalDateTime.of(2026, 8, 3, 9, 0)),
			LocalDateTime.of(2026, 8, 10, 9, 0));
		InvestmentPracticeResponse response = new InvestmentPracticeResponse(
			"INVESTMENT_PRACTICE_V1", "COMPLETED", null, steps, LocalDateTime.of(2026, 8, 10, 9, 0), 5_000_000L,
			attempt);
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
			.andExpect(jsonPath("$.steps[0].evidence.reflectionId").value(70))
			.andExpect(jsonPath("$.steps[0].evidence.buyQuantity").value(10))
			.andExpect(jsonPath("$.steps[0].evidence.sellQuantity").value(4))
			.andExpect(jsonPath("$.steps[0].evidence.remainingQuantity").value(6))
			.andExpect(jsonPath("$.steps[0].evidence.tradeResult.buyPrice").value(10000.00000000))
			.andExpect(jsonPath("$.steps[0].evidence.tradeResult.sellPrice").value(10500.00000000))
			.andExpect(jsonPath("$.steps[0].evidence.tradeResult.realizedPnl").value(4985))
			.andExpect(jsonPath("$.steps[0].evidence.tradeResult.returnRate").value(0.0500))
			.andExpect(jsonPath("$.steps[0].evidence.tradeResult.sellVerdict").value("ABOVE_TAKE_PROFIT"))
			.andExpect(jsonPath("$.attempt.mode").value("REPLAY"))
			.andExpect(jsonPath("$.attempt.runNumber").value(2))
			.andExpect(jsonPath("$.attempt.riskSnapshot.entryPrice").value(100.00000000))
			.andExpect(jsonPath("$.attempt.riskSnapshot.stopLossPrice").value(97.00000000))
			.andExpect(jsonPath("$.attempt.riskSnapshot.takeProfitPrice").value(105.00000000))
			.andExpect(jsonPath("$.rewardAmount").value(5_000_000));
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
			"COIN_PRACTICE_V1", "NOT_STARTED", 1, steps, null, null, null);
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
			// 이슈 #421: 빈 evidence(잠긴 단계·legacy chain)에서는 tradeResult 객체 자체가 없어야 한다.
			.andExpect(jsonPath("$.steps[0].evidence.tradeResult").doesNotExist())
			.andExpect(jsonPath("$.steps[1].locked").value(true))
			.andExpect(jsonPath("$.steps[2].locked").value(true));
	}

	@Test
	void getProgressSerializesTradeResultWithOnlyBuyPriceWhileAwaitingSale() throws Exception {
		authenticate();
		// 매도 전(AWAITING_SALE)에는 tradeResult 객체는 나가되 buyPrice만 값이 있고 나머지 넷은 null이다 —
		// 객체가 통째로 null인 legacy·빈 evidence와 구분돼야 프론트가 "매수는 했고 아직 안 팔았다"를 안다.
		PracticeTradeResultResponse awaitingSale = new PracticeTradeResultResponse(
			new BigDecimal("10000.00000000"), null, null, null, null);
		PracticeEvidenceResponse evidence = new PracticeEvidenceResponse(
			null, null, null, null, 30L, LocalDateTime.of(2026, 8, 3, 9, 0), 40L,
			new BigDecimal("9700.00000000"), new BigDecimal("10500.00000000"), 60L,
			LocalDateTime.of(2026, 8, 3, 9, 1), "CLOSER_TO_BOUNDARY", null, null, null, null,
			LocalDateTime.of(2026, 8, 3, 9, 5), new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("10"),
			awaitingSale);
		List<PracticeStepResponse> steps = List.of(
			new PracticeStepResponse(1, "COMPLETED", false, PracticeEvidenceResponse.empty()),
			new PracticeStepResponse(2, "COMPLETED", false, evidence),
			new PracticeStepResponse(3, "COMPLETED", false, evidence),
			new PracticeStepResponse(4, "AWAITING_SALE", false, evidence));
		InvestmentPracticeResponse response = new InvestmentPracticeResponse(
			"INVESTMENT_PRACTICE_V1", "IN_PROGRESS", 4, steps, null, null, null);
		when(investmentPracticeQueryService.getProgress(eq(USER_ID), eq(Market.STOCK))).thenReturn(response);

		mockMvc.perform(get("/api/education/practice")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.steps[3].status").value("AWAITING_SALE"))
			.andExpect(jsonPath("$.steps[3].evidence.tradeResult.buyPrice").value(10000.00000000))
			.andExpect(jsonPath("$.steps[3].evidence.tradeResult.sellPrice").doesNotExist())
			.andExpect(jsonPath("$.steps[3].evidence.tradeResult.realizedPnl").doesNotExist())
			.andExpect(jsonPath("$.steps[3].evidence.tradeResult.returnRate").doesNotExist())
			.andExpect(jsonPath("$.steps[3].evidence.tradeResult.sellVerdict").doesNotExist())
			// 1단계의 빈 evidence는 같은 응답 안에서도 tradeResult가 없어야 한다.
			.andExpect(jsonPath("$.steps[0].evidence.tradeResult").doesNotExist());
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
			"INVESTMENT_PRACTICE_V1", "IN_PROGRESS", 2, steps, null, null, null);
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
