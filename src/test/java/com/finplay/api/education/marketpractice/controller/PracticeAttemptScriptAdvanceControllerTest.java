// 2단계 -> 3단계 대본 전환 API의 인증, 시장 검증, JSON 응답과 오류 매핑을 검증한다.
package com.finplay.api.education.marketpractice.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.dto.response.ExitPresetResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.service.PracticeAttemptScriptAdvanceService;
import com.finplay.api.market.domain.Market;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PracticeAttemptScriptAdvanceController.class)
@Import(SecurityConfig.class)
class PracticeAttemptScriptAdvanceControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private PracticeAttemptScriptAdvanceService scriptAdvanceService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void advanceScriptRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(post("/api/education/practice/attempts/CRYPTO/advance-script"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(scriptAdvanceService);
	}

	@Test
	void advanceScriptReturnsSwitchedAttemptJson() throws Exception {
		authenticate();
		PracticeAttemptResponse response = new PracticeAttemptResponse(
			11L, "CRYPTO", 2L, "ACTIVE", "ACTIVE", 3L, null, null, null, null,
			10_000_000L, 10_000_000L, 0L,
			"BALANCED", false, ExitPresetResponse.all());
		when(scriptAdvanceService.advanceScript(USER_ID, Market.CRYPTO)).thenReturn(response);

		mockMvc.perform(post("/api/education/practice/attempts/CRYPTO/advance-script")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attemptId").value(11))
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.runNumber").value(2))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.tutorialCashBalance").value(10_000_000));
		verify(scriptAdvanceService).advanceScript(USER_ID, Market.CRYPTO);
	}

	@Test
	void advanceScriptRejectsInvalidMarketPath() throws Exception {
		authenticate();
		mockMvc.perform(post("/api/education/practice/attempts/FOREX/advance-script")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(scriptAdvanceService);
	}

	@Test
	void advanceScriptMapsStageLockedToConflict() throws Exception {
		authenticate();
		when(scriptAdvanceService.advanceScript(USER_ID, Market.CRYPTO))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_STAGE_LOCKED));

		mockMvc.perform(post("/api/education/practice/attempts/CRYPTO/advance-script")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRACTICE_STAGE_LOCKED"));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}
}
