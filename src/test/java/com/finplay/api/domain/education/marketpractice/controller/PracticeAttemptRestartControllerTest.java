// 튜토리얼 attempt 재시작 API의 인증, 시장 검증, JSON 응답과 오류 매핑을 검증한다.
package com.finplay.api.domain.education.marketpractice.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.education.marketpractice.dto.response.ExitPresetResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.ExitRateBoundsResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.service.PracticeAttemptDeadlockRetryService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PracticeAttemptRestartController.class)
@Import(SecurityConfig.class)
class PracticeAttemptRestartControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;
	// 컨트롤러는 교착 재시도 경계만 주입받는다 (이슈 #491).
	@MockitoBean
	private PracticeAttemptDeadlockRetryService retryService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void restartRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(post("/api/education/practice/attempts/STOCK/restart"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(retryService);
	}

	@Test
	void restartReturnsResetAttemptJson() throws Exception {
		authenticate();
		PracticeAttemptResponse response = new PracticeAttemptResponse(
			11L, "CRYPTO", 2L, "ACTIVE", "SELECTING_INSTRUMENT", null, null, null, null, null,
			10_000_000L, 10_000_000L, 0L,
			"BALANCED", false, ExitPresetResponse.all(),
			new BigDecimal("3"), new BigDecimal("5"), ExitRateBoundsResponse.current());
		when(retryService.restart(USER_ID, Market.CRYPTO)).thenReturn(response);

		mockMvc.perform(post("/api/education/practice/attempts/CRYPTO/restart")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attemptId").value(11))
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.runNumber").value(2))
			.andExpect(jsonPath("$.mode").value("ACTIVE"))
			.andExpect(jsonPath("$.status").value("SELECTING_INSTRUMENT"))
			.andExpect(jsonPath("$.tutorialCashBalance").value(10_000_000))
			.andExpect(jsonPath("$.tutorialAvailableCash").value(10_000_000))
			.andExpect(jsonPath("$.tutorialRealizedPnl").value(0));
		verify(retryService).restart(USER_ID, Market.CRYPTO);
	}

	@Test
	void restartCompletedAttemptReturnsActiveResetJson() throws Exception {
		authenticate();
		PracticeAttemptResponse response = new PracticeAttemptResponse(
			11L, "CRYPTO", 3L, "ACTIVE", "SELECTING_INSTRUMENT", null, null, null, null, null,
			10_000_000L, 10_000_000L, 0L,
			"BALANCED", false, ExitPresetResponse.all(),
			new BigDecimal("3"), new BigDecimal("5"), ExitRateBoundsResponse.current());
		when(retryService.restart(USER_ID, Market.CRYPTO)).thenReturn(response);

		mockMvc.perform(post("/api/education/practice/attempts/CRYPTO/restart")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runNumber").value(3))
			.andExpect(jsonPath("$.mode").value("ACTIVE"))
			.andExpect(jsonPath("$.status").value("SELECTING_INSTRUMENT"))
			.andExpect(jsonPath("$.completedAt").doesNotExist());
		verify(retryService).restart(USER_ID, Market.CRYPTO);
	}

	@Test
	void restartRejectsInvalidMarketPath() throws Exception {
		authenticate();
		mockMvc.perform(post("/api/education/practice/attempts/FOREX/restart")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(retryService);
	}

	@Test
	void restartMapsEvidenceMismatchToConflict() throws Exception {
		authenticate();
		when(retryService.restart(USER_ID, Market.STOCK))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		mockMvc.perform(post("/api/education/practice/attempts/STOCK/restart")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRACTICE_EVIDENCE_MISSING"));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}
}
