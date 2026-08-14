// 튜토리얼 차트 조회·tick API의 인증, JSON 계약과 잠금 오류 매핑을 검증한다.
package com.finplay.api.education.marketpractice.controller;

import static org.mockito.Mockito.verify;
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
import com.finplay.api.education.marketpractice.dto.response.PracticeTutorialCandleResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.education.marketpractice.service.PracticeAttemptChartService;
import com.finplay.api.market.domain.Market;
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
		when(chartService.tick(USER_ID, Market.STOCK)).thenReturn(chartResponse());

		mockMvc.perform(post("/api/education/practice/attempts/STOCK/tick")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.candles.length()").value(30));
		verify(chartService).tick(USER_ID, Market.STOCK);
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
		when(chartService.tick(USER_ID, Market.CRYPTO))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED));

		mockMvc.perform(post("/api/education/practice/attempts/CRYPTO/tick")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRACTICE_ALREADY_COMPLETED"));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private static PracticeTutorialChartResponse chartResponse() {
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
			11L, 3L, 21L, LocalDateTime.of(2026, 8, 14, 12, 7), 3, candles);
	}
}
