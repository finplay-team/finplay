// 실습 진행 조회 API(GET /api/education/practice)의 인증·검증·응답 매핑을 검증하는 WebMvc 테스트다.
package com.finplay.api.education.marketpractice.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.education.marketpractice.dto.response.InvestmentPracticeResponse;
import com.finplay.api.education.marketpractice.dto.response.ExitPresetResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeEntryResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeEvidenceResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeScenarioEventResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeRiskSnapshotResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeStageProgressResponse;
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
			"ABOVE_TAKE_PROFIT", "MANUAL");
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
				new BigDecimal("105.00000000"), 30L, LocalDateTime.of(2026, 8, 3, 9, 0),
				"BALANCED", new BigDecimal("3"), new BigDecimal("5"), 1),
			LocalDateTime.of(2026, 8, 10, 9, 0), 0L, 0L, 0L,
			"BALANCED", false, ExitPresetResponse.all());
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
			new BigDecimal("10000.00000000"), null, null, null, null, "MANUAL");
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

	// 041 6번 — 진입별 대조 배열이 실제 JSON으로 나가는지 본다. 계산은 서비스가 하지만 **재조립 경로가
	// chart와 달라**(withEntryComparison이 응답을 통째로 다시 만든다) 여기서 한 번 더 계약을 고정한다.
	@Test
	void getProgressSerializesPerEntryComparisonWithRevealedEventsAndPriceAfterSell() throws Exception {
		authenticate();
		InvestmentPracticeResponse response = new InvestmentPracticeResponse(
			"COIN_PRACTICE_V1", "COMPLETED", null,
			List.of(new PracticeStepResponse(1, "COMPLETED", false, PracticeEvidenceResponse.empty())),
			LocalDateTime.of(2026, 8, 20, 12, 0), 5_000_000L, null,
			List.of(new PracticeScenarioEventResponse("ACT1", "[연습] 첫 소식")),
			new BigDecimal("7900.00000000"),
			List.of(
				new PracticeEntryResponse(1, "CAUTIOUS", "MARKET", LocalDateTime.of(2026, 8, 20, 11, 0),
					new BigDecimal("10000.00000000"), new BigDecimal("1"), new BigDecimal("9800.00000000"),
					new BigDecimal("10300.00000000"), new BigDecimal("9750.00000000"), new BigDecimal("1"),
					LocalDateTime.of(2026, 8, 20, 11, 10), "STOP_LOSS", -260L, -2108L),
				new PracticeEntryResponse(2, "BALANCED", "LIMIT", LocalDateTime.of(2026, 8, 20, 11, 20),
					new BigDecimal("8700.00000000"), new BigDecimal("1"), new BigDecimal("8439.00000000"),
					new BigDecimal("9135.00000000"), new BigDecimal("9135.00000000"), new BigDecimal("1"),
					LocalDateTime.of(2026, 8, 20, 11, 40), "TAKE_PROFIT", 422L, -807L)),
			new PracticeStageProgressResponse(true, true, true));
		when(investmentPracticeQueryService.getProgress(eq(USER_ID), eq(Market.CRYPTO))).thenReturn(response);

		mockMvc.perform(get("/api/education/practice")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.param("market", "CRYPTO"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.priceAfterSell").value(7900.00000000))
			.andExpect(jsonPath("$.revealedEvents.length()").value(1))
			.andExpect(jsonPath("$.revealedEvents[0].stage").value("ACT1"))
			.andExpect(jsonPath("$.entries.length()").value(2))
			// 재진입한 실행의 두 매도가 각각 보인다 — 실행 전체 요약은 첫 매도만 가리킨다.
			.andExpect(jsonPath("$.entries[0].entrySequence").value(1))
			.andExpect(jsonPath("$.entries[0].exitPreset").value("CAUTIOUS"))
			.andExpect(jsonPath("$.entries[0].sellCause").value("STOP_LOSS"))
			.andExpect(jsonPath("$.entries[0].sellQuantity").value(1))
			.andExpect(jsonPath("$.entries[0].unrealizedPnlIfHeld").value(-2108))
			.andExpect(jsonPath("$.entries[1].entrySequence").value(2))
			.andExpect(jsonPath("$.entries[1].sellCause").value("TAKE_PROFIT"))
			.andExpect(jsonPath("$.entries[1].unrealizedPnlIfHeld").value(-807))
			// 이슈 #503 — 진입마다 매수의 주문 유형이 따로 나간다. 완료 화면이 "이 진입은 시장가,
			// 저 진입은 지정가"를 구분해 그리는 근거다.
			.andExpect(jsonPath("$.entries[0].buyOrderType").value("MARKET"))
			.andExpect(jsonPath("$.entries[1].buyOrderType").value("LIMIT"))
			.andExpect(jsonPath("$.tutorialStageProgress.marketBuySellCompleted").value(true))
			.andExpect(jsonPath("$.tutorialStageProgress.limitBuySellCompleted").value(true))
			.andExpect(jsonPath("$.tutorialStageProgress.exitPresetSelected").value(true));
	}

	// SCENARIO-015 — 공개 전 구간의 진행 조회에도 문안·개수·자리표시자가 남지 않는다.
	@Test
	void getProgressCarriesNoTraceOfUnrevealedEvents() throws Exception {
		authenticate();
		InvestmentPracticeResponse response = new InvestmentPracticeResponse(
			"COIN_PRACTICE_V1", "IN_PROGRESS", 3,
			List.of(new PracticeStepResponse(1, "COMPLETED", false, PracticeEvidenceResponse.empty())),
			null, null, null, List.of(), new BigDecimal("9950.00000000"), List.of(),
			new PracticeStageProgressResponse(true, false, false));
		when(investmentPracticeQueryService.getProgress(eq(USER_ID), eq(Market.CRYPTO))).thenReturn(response);

		mockMvc.perform(get("/api/education/practice")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.param("market", "CRYPTO"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revealedEvents.length()").value(0))
			.andExpect(jsonPath("$.entries.length()").value(0))
			// 시장가만 마친 진행 중 화면 — 지정가·프리셋 단계는 아직 잠겨 있어야 한다.
			.andExpect(jsonPath("$.tutorialStageProgress.marketBuySellCompleted").value(true))
			.andExpect(jsonPath("$.tutorialStageProgress.limitBuySellCompleted").value(false))
			.andExpect(jsonPath("$.tutorialStageProgress.exitPresetSelected").value(false))
			.andExpect(content().string(not(containsString("[연습]"))));
	}

	// legacy chain 경로는 편의 생성자를 쓴다 — 세 필드가 "이 경로에는 없다"로 나가는지 고정한다.
	@Test
	void getProgressLeavesComparisonFieldsEmptyForLegacyChainResponses() throws Exception {
		authenticate();
		InvestmentPracticeResponse response = new InvestmentPracticeResponse(
			"INVESTMENT_PRACTICE_V1", "IN_PROGRESS", 2,
			List.of(new PracticeStepResponse(1, "COMPLETED", false, PracticeEvidenceResponse.empty())),
			null, null, null);
		when(investmentPracticeQueryService.getProgress(eq(USER_ID), eq(Market.STOCK))).thenReturn(response);

		mockMvc.perform(get("/api/education/practice")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entries.length()").value(0))
			.andExpect(jsonPath("$.revealedEvents.length()").value(0))
			.andExpect(jsonPath("$.priceAfterSell").doesNotExist())
			// 이슈 #503 — attempt가 없는 경로도 `null`이 아니라 세 값 모두 false인 객체로 나간다.
			// 짧은 생성자가 채우는 값이라 여기서만 계약이 고정된다(다른 두 테스트는 값을 채워 stub한다).
			.andExpect(jsonPath("$.tutorialStageProgress").exists())
			.andExpect(jsonPath("$.tutorialStageProgress.marketBuySellCompleted").value(false))
			.andExpect(jsonPath("$.tutorialStageProgress.limitBuySellCompleted").value(false))
			.andExpect(jsonPath("$.tutorialStageProgress.exitPresetSelected").value(false));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}
}
