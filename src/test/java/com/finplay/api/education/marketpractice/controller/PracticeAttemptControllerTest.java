// 튜토리얼 attempt 진입·종목 선택 API의 인증, JSON 계약, 입력 검증과 오류 매핑을 검증한다.
package com.finplay.api.education.marketpractice.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.ExitPreset;
import com.finplay.api.education.marketpractice.dto.response.ExitPresetResponse;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.service.PracticeAttemptEntryService;
import com.finplay.api.education.marketpractice.service.PracticeAttemptService;
import com.finplay.api.market.domain.Market;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PracticeAttemptController.class)
@Import(SecurityConfig.class)
class PracticeAttemptControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PracticeAttemptService practiceAttemptService;

	// 진입만 재시도 경계(PracticeAttemptEntryService)를 거친다 (이슈 #491).
	@MockitoBean
	private PracticeAttemptEntryService practiceAttemptEntryService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void ensureAttemptRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(put("/api/education/practice/attempts/STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(practiceAttemptEntryService);
	}

	@Test
	void ensureAttemptReturnsActiveAttemptJson() throws Exception {
		authenticate();
		PracticeAttemptResponse response = new PracticeAttemptResponse(
			11L, "STOCK", 1L, "ACTIVE", "SELECTING_INSTRUMENT", null, null, null, null, null,
			10_000_000L, 10_000_000L, 0L,
			"BALANCED", false, ExitPresetResponse.all());
		when(practiceAttemptEntryService.ensureAttempt(USER_ID, Market.STOCK)).thenReturn(response);

		mockMvc.perform(put("/api/education/practice/attempts/STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attemptId").value(11))
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.runNumber").value(1))
			.andExpect(jsonPath("$.mode").value("ACTIVE"))
			.andExpect(jsonPath("$.status").value("SELECTING_INSTRUMENT"))
			.andExpect(jsonPath("$.tutorialCashBalance").value(10_000_000))
			.andExpect(jsonPath("$.tutorialAvailableCash").value(10_000_000))
			.andExpect(jsonPath("$.tutorialRealizedPnl").value(0));

		verify(practiceAttemptEntryService).ensureAttempt(USER_ID, Market.STOCK);
	}

	@Test
	void ensureAttemptRejectsInvalidMarketPath() throws Exception {
		authenticate();

		mockMvc.perform(put("/api/education/practice/attempts/FOREX")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(practiceAttemptEntryService);
	}

	@Test
	void selectInstrumentReturnsInProgressAttemptJson() throws Exception {
		authenticate();
		PracticeAttemptResponse response = new PracticeAttemptResponse(
			11L,
			"CRYPTO",
			1L,
			"ACTIVE",
			"IN_PROGRESS",
			21L,
			LocalDateTime.of(2026, 8, 14, 12, 0),
			LocalDate.of(2026, 8, 14),
			null,
			null,
			0L,
			0L,
			0L,
			"BALANCED", false, ExitPresetResponse.all());
		when(practiceAttemptService.selectInstrument(USER_ID, Market.CRYPTO, 21L)).thenReturn(response);

		mockMvc.perform(put("/api/education/practice/attempts/CRYPTO/instrument")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"instrumentId\":21}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attemptId").value(11))
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.instrumentId").value(21))
			.andExpect(jsonPath("$.anchorAt").value("2026-08-14T12:00:00"))
			.andExpect(jsonPath("$.tutorialDate").value("2026-08-14"))
			// TUTORIAL-CASH-ISOL-011 범위는 진입·재시작 응답 한정 — 종목 선택 응답은 튜토리얼 계좌를
			// 다시 조회하지 않으므로 세 필드 모두 0을 반환하는 것이 설계 의도다(api-contracts.md 명시).
			.andExpect(jsonPath("$.tutorialCashBalance").value(0))
			.andExpect(jsonPath("$.tutorialAvailableCash").value(0))
			.andExpect(jsonPath("$.tutorialRealizedPnl").value(0));

		verify(practiceAttemptService).selectInstrument(USER_ID, Market.CRYPTO, 21L);
	}

	@Test
	void selectInstrumentRejectsMissingInstrumentId() throws Exception {
		authenticate();

		mockMvc.perform(put("/api/education/practice/attempts/STOCK/instrument")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("종목 ID는 필수입니다."));

		verifyNoInteractions(practiceAttemptService);
	}

	@Test
	void selectInstrumentRejectsNonPositiveInstrumentId() throws Exception {
		authenticate();

		mockMvc.perform(put("/api/education/practice/attempts/STOCK/instrument")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"instrumentId\":0}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("종목 ID는 양수여야 합니다."));

		verifyNoInteractions(practiceAttemptService);
	}

	// 042 EXITPRESET-003 — 정의 밖 문자열은 Jackson이 열거형으로 못 바꿔 400 VALIDATION_ERROR가 된다.
	@Test
	void selectExitPresetReturnsUpdatedAttempt() throws Exception {
		authenticate();
		PracticeAttemptResponse response = new PracticeAttemptResponse(
			11L, "CRYPTO", 1L, "ACTIVE", "IN_PROGRESS", 21L, null, null, null, null,
			0L, 0L, 0L,
			"CAUTIOUS", false, ExitPresetResponse.all());
		when(practiceAttemptService.selectExitPreset(USER_ID, Market.CRYPTO, ExitPreset.CAUTIOUS))
			.thenReturn(response);

		mockMvc.perform(put("/api/education/practice/attempts/CRYPTO/exit-preset")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"preset\":\"CAUTIOUS\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.selectedExitPreset").value("CAUTIOUS"))
			.andExpect(jsonPath("$.exitPresetLocked").value(false))
			.andExpect(jsonPath("$.availableExitPresets.length()").value(3))
			.andExpect(jsonPath("$.availableExitPresets[0].preset").value("CAUTIOUS"))
			.andExpect(jsonPath("$.availableExitPresets[0].stopLossRate").value(2));
	}

	@Test
	void selectExitPresetRejectsUnknownPresetWithoutCallingService() throws Exception {
		authenticate();

		mockMvc.perform(put("/api/education/practice/attempts/CRYPTO/exit-preset")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"preset\":\"AGGRESSIVE\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(practiceAttemptService);
	}

	@Test
	void selectExitPresetRejectsMissingPresetWithoutCallingService() throws Exception {
		authenticate();

		mockMvc.perform(put("/api/education/practice/attempts/CRYPTO/exit-preset")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(practiceAttemptService);
	}

	@Test
	void selectInstrumentMapsBusinessErrorToConflict() throws Exception {
		authenticate();
		when(practiceAttemptService.selectInstrument(USER_ID, Market.STOCK, 21L))
			.thenThrow(new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE));

		mockMvc.perform(put("/api/education/practice/attempts/STOCK/instrument")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"instrumentId\":21}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSTRUMENT_NOT_TRADABLE"))
			.andExpect(jsonPath("$.error.message").value("거래할 수 없는 종목입니다."));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}
}
