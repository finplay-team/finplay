package com.finplay.api.domain.education.marketpractice.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeScenarioEventResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTutorialCandleResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PriceGuideRangeResponse;
import com.finplay.api.domain.education.marketpractice.service.PracticeAttemptChartService;
import com.finplay.api.domain.education.marketpractice.service.PracticeAttemptDeadlockRetryService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PracticeAttemptChartController.class)
@Import(SecurityConfig.class)
class PracticeAttemptChartControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private PracticeAttemptChartService chartService;
	@MockitoBean
	private PracticeAttemptDeadlockRetryService retryService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void getChartRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(get("/api/education/practice/attempts/CRYPTO/chart"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(chartService);
	}

	@Test
	void getChartReturnsThirtyOrderedCandlesAndCurrentCloseJson() throws Exception {
		authenticate();
		when(chartService.getChart(USER_ID, Market.CRYPTO)).thenReturn(chartResponse());

		mockMvc.perform(get("/api/education/practice/attempts/CRYPTO/chart")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attemptId").value(11))
			.andExpect(jsonPath("$.runNumber").value(3))
			.andExpect(jsonPath("$.instrumentId").value(21))
			.andExpect(jsonPath("$.virtualDateTime").value("2026-08-14T12:07:00"))
			.andExpect(jsonPath("$.secondsPerVirtualMinute").value(3))
			.andExpect(jsonPath("$.candles.length()").value(30))
			.andExpect(jsonPath("$.candles[0].date").value("2026-07-16"))
			.andExpect(jsonPath("$.candles[29].date").value("2026-08-14"))
			.andExpect(jsonPath("$.candles[29].close").value(10932.45600000))
			.andExpect(jsonPath("$.candles[29].current").value(true));
	}

	@Test
	void tickDelegatesExplicitSettlementAndReturnsChartJson() throws Exception {
		authenticate();
		when(retryService.tick(USER_ID, Market.STOCK)).thenReturn(chartResponse());

		mockMvc.perform(post("/api/education/practice/attempts/STOCK/tick")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.candles.length()").value(30));
		verify(retryService).tick(USER_ID, Market.STOCK);
	}

	@Test
	void getChartMapsLockedStepToConflict() throws Exception {
		authenticate();
		when(chartService.getChart(USER_ID, Market.CRYPTO))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));

		mockMvc.perform(get("/api/education/practice/attempts/CRYPTO/chart")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRACTICE_STEP_LOCKED"));
	}

	@Test
	void tickMapsCompletedAttemptToConflict() throws Exception {
		authenticate();
		when(retryService.tick(USER_ID, Market.CRYPTO))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED));

		mockMvc.perform(post("/api/education/practice/attempts/CRYPTO/tick")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRACTICE_ALREADY_COMPLETED"));
	}

	@Test
	void chartJsonCarriesNoTraceOfUnrevealedEvents() throws Exception {
		authenticate();
		when(chartService.getChart(USER_ID, Market.CRYPTO))
			.thenReturn(chartResponse("ACT2", true, "NONE_KNOWN", List.of()));

		mockMvc.perform(get("/api/education/practice/attempts/CRYPTO/chart")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scenarioStage").value("ACT2"))
			.andExpect(jsonPath("$.scenarioProgressing").value(true))
			.andExpect(jsonPath("$.causeStatus").value("NONE_KNOWN"))
			.andExpect(jsonPath("$.revealedEvents.length()").value(0))
			.andExpect(content().string(not(containsString("[연습]"))));
	}

	@Test
	void chartJsonExposesRevealedEventsWithoutAnyTimestamp() throws Exception {
		authenticate();
		when(chartService.getChart(USER_ID, Market.CRYPTO)).thenReturn(chartResponse(
			"ACT2", true, "REVEALED",
			List.of(new PracticeScenarioEventResponse("ACT1", "[연습] 첫 소식"),
				new PracticeScenarioEventResponse("ACT2", "[연습] 두 번째 소식"))));

		mockMvc.perform(get("/api/education/practice/attempts/CRYPTO/chart")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.causeStatus").value("REVEALED"))
			.andExpect(jsonPath("$.revealedEvents.length()").value(2))
			.andExpect(jsonPath("$.revealedEvents[0].stage").value("ACT1"))
			.andExpect(jsonPath("$.revealedEvents[0].headline").value("[연습] 첫 소식"))
			.andExpect(jsonPath("$.revealedEvents[1].headline").value("[연습] 두 번째 소식"))
			.andExpect(jsonPath("$.revealedEvents[0].length()").value(2));
	}

	@Test
	void chartJsonLeavesScenarioFieldsNullForNonScriptAttempts() throws Exception {
		authenticate();
		when(chartService.getChart(USER_ID, Market.STOCK)).thenReturn(chartResponse(null, null, null, List.of()));

		mockMvc.perform(get("/api/education/practice/attempts/STOCK/chart")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scenarioStage").value(nullValue()))
			.andExpect(jsonPath("$.scenarioProgressing").value(nullValue()))
			.andExpect(jsonPath("$.causeStatus").value(nullValue()))
			.andExpect(jsonPath("$.revealedEvents.length()").value(0));
	}

	@Test
	void chartJsonExposesPriceGuideRangeWhenScriptHasNoEvents() throws Exception {
		authenticate();
		PriceGuideRangeResponse range = new PriceGuideRangeResponse(
			new BigDecimal("90000.00000000"), new BigDecimal("110000.00000000"));
		when(chartService.getChart(USER_ID, Market.CRYPTO)).thenReturn(chartResponse(range));

		mockMvc.perform(get("/api/education/practice/attempts/CRYPTO/chart")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.priceGuideRange.low").value(90000.00000000))
			.andExpect(jsonPath("$.priceGuideRange.high").value(110000.00000000));
	}

	@Test
	void chartJsonLeavesPriceGuideRangeNullAndNeverLeaksActFourCrashPrice() throws Exception {
		authenticate();
		when(chartService.getChart(USER_ID, Market.CRYPTO)).thenReturn(chartResponse((PriceGuideRangeResponse)null));

		mockMvc.perform(get("/api/education/practice/attempts/CRYPTO/chart")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.priceGuideRange").value(nullValue()))
			.andExpect(content().string(not(containsString("7900"))));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private static PracticeTutorialChartResponse chartResponse() {
		return chartResponse("ACT2", false, "NONE_KNOWN", List.of());
	}

	private static PracticeTutorialChartResponse chartResponse(PriceGuideRangeResponse priceGuideRange) {
		return chartResponse("ACT2", false, "NONE_KNOWN", List.of(), priceGuideRange);
	}

	private static PracticeTutorialChartResponse chartResponse(
		String scenarioStage, Boolean progressing, String causeStatus, List<PracticeScenarioEventResponse> events) {
		return chartResponse(scenarioStage, progressing, causeStatus, events, null);
	}

	private static PracticeTutorialChartResponse chartResponse(
		String scenarioStage, Boolean progressing, String causeStatus, List<PracticeScenarioEventResponse> events,
		PriceGuideRangeResponse priceGuideRange) {
		List<PracticeTutorialCandleResponse> candles = IntStream.range(0, 30)
			.mapToObj(index -> new PracticeTutorialCandleResponse(
				LocalDate.of(2026, 7, 16).plusDays(index),
				BigDecimal.valueOf(10_000 + index),
				BigDecimal.valueOf(11_000 + index),
				BigDecimal.valueOf(9_000 + index),
				index == 29 ? new BigDecimal("10932.45600000") : BigDecimal.valueOf(10_100 + index),
				index == 29))
			.toList();
		return new PracticeTutorialChartResponse(
			11L, 3L, 21L, LocalDateTime.of(2026, 8, 14, 12, 7), 3, candles,
			scenarioStage, progressing, causeStatus, events, priceGuideRange);
	}
}
