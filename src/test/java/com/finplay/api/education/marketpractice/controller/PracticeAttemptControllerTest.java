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
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
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

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void ensureAttemptRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(put("/api/education/practice/attempts/STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(practiceAttemptService);
	}

	@Test
	void ensureAttemptReturnsActiveAttemptJson() throws Exception {
		authenticate();
		PracticeAttemptResponse response = new PracticeAttemptResponse(
			11L, "STOCK", 1L, "ACTIVE", "SELECTING_INSTRUMENT", null, null, null, null, null);
		when(practiceAttemptService.ensureAttempt(USER_ID, Market.STOCK)).thenReturn(response);

		mockMvc.perform(put("/api/education/practice/attempts/STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attemptId").value(11))
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.runNumber").value(1))
			.andExpect(jsonPath("$.mode").value("ACTIVE"))
			.andExpect(jsonPath("$.status").value("SELECTING_INSTRUMENT"));

		verify(practiceAttemptService).ensureAttempt(USER_ID, Market.STOCK);
	}

	@Test
	void ensureAttemptRejectsInvalidMarketPath() throws Exception {
		authenticate();

		mockMvc.perform(put("/api/education/practice/attempts/FOREX")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(practiceAttemptService);
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
			null);
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
			.andExpect(jsonPath("$.tutorialDate").value("2026-08-14"));

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
