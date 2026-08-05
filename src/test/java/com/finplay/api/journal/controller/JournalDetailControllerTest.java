// 투자일기 상세 조회 API(매수·매도 경로 분리)의 인증, 검증, 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.journal.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.journal.dto.response.BuyJournalDetailResponse;
import com.finplay.api.journal.dto.response.SellJournalDetailResponse;
import com.finplay.api.journal.service.JournalService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JournalDetailController.class)
@Import(SecurityConfig.class)
class JournalDetailControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;
	private static final long BUY_TRADE_ID = 12L;
	private static final long SELL_TRADE_ID = 34L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private JournalService journalService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	private void stubAuthenticatedUser() {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	@Test
	void getBuyJournalReturnsOkWithEveryResponseFieldAndNoSellTradeIdKey() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime createdAt = LocalDateTime.of(2026, 8, 4, 10, 12, 33);
		LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 5, 8, 47, 5);
		BuyJournalDetailResponse response = new BuyJournalDetailResponse(
			1L, BUY_TRADE_ID, "실적 발표 전 분할 매수. 5% 빠지면 손절 계획.", createdAt, updatedAt);
		when(journalService.getBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID))).thenReturn(response);

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.journalId").value(1))
			.andExpect(jsonPath("$.buyTradeId").value(BUY_TRADE_ID))
			.andExpect(jsonPath("$.content").value("실적 발표 전 분할 매수. 5% 빠지면 손절 계획."))
			.andExpect(jsonPath("$.createdAt").value("2026-08-04T10:12:33"))
			.andExpect(jsonPath("$.updatedAt").value("2026-08-05T08:47:05"))
			.andExpect(jsonPath("$.sellTradeId").doesNotExist());

		verify(journalService).getBuyJournal(USER_ID, BUY_TRADE_ID);
	}

	@Test
	void getBuyJournalRejectsNonNumericBuyTradeIdWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", "abc")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void getBuyJournalRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", BUY_TRADE_ID))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void getBuyJournalReturnsNotFoundWhenServiceRejectsMissingTrade() throws Exception {
		stubAuthenticatedUser();
		when(journalService.getBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID)))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).getBuyJournal(USER_ID, BUY_TRADE_ID);
	}

	@Test
	void getBuyJournalReturnsNotFoundWhenServiceRejectsMissingJournal() throws Exception {
		stubAuthenticatedUser();
		when(journalService.getBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID)))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).getBuyJournal(USER_ID, BUY_TRADE_ID);
	}

	@Test
	void getBuyJournalReturnsForbiddenWhenServiceRejectsOwnership() throws Exception {
		stubAuthenticatedUser();
		when(journalService.getBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID)))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).getBuyJournal(USER_ID, BUY_TRADE_ID);
	}

	@Test
	void getBuyJournalReturnsBadRequestWhenServiceRejectsNonBuyTrade() throws Exception {
		stubAuthenticatedUser();
		when(journalService.getBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID)))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		mockMvc.perform(get("/api/journal/buy/{buyTradeId}", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).getBuyJournal(USER_ID, BUY_TRADE_ID);
	}

	@Test
	void getSellJournalReturnsOkWithEveryResponseFieldAndNoBuyTradeIdKey() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime createdAt = LocalDateTime.of(2026, 8, 4, 15, 20, 41);
		LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 5, 9, 3, 12);
		SellJournalDetailResponse response = new SellJournalDetailResponse(
			2L, SELL_TRADE_ID, "목표가 도달해 전량 매도. 다음엔 좀 더 분할로 팔아보자.", createdAt, updatedAt);
		when(journalService.getSellJournal(eq(USER_ID), eq(SELL_TRADE_ID))).thenReturn(response);

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.journalId").value(2))
			.andExpect(jsonPath("$.sellTradeId").value(SELL_TRADE_ID))
			.andExpect(jsonPath("$.content").value("목표가 도달해 전량 매도. 다음엔 좀 더 분할로 팔아보자."))
			.andExpect(jsonPath("$.createdAt").value("2026-08-04T15:20:41"))
			.andExpect(jsonPath("$.updatedAt").value("2026-08-05T09:03:12"))
			.andExpect(jsonPath("$.buyTradeId").doesNotExist());

		verify(journalService).getSellJournal(USER_ID, SELL_TRADE_ID);
	}

	@Test
	void getSellJournalRejectsNonNumericSellTradeIdWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", "abc")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void getSellJournalRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", SELL_TRADE_ID))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void getSellJournalReturnsNotFoundWhenServiceRejectsMissingTrade() throws Exception {
		stubAuthenticatedUser();
		when(journalService.getSellJournal(eq(USER_ID), eq(SELL_TRADE_ID)))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).getSellJournal(USER_ID, SELL_TRADE_ID);
	}

	@Test
	void getSellJournalReturnsNotFoundWhenServiceRejectsMissingJournal() throws Exception {
		stubAuthenticatedUser();
		when(journalService.getSellJournal(eq(USER_ID), eq(SELL_TRADE_ID)))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).getSellJournal(USER_ID, SELL_TRADE_ID);
	}

	@Test
	void getSellJournalReturnsForbiddenWhenServiceRejectsOwnership() throws Exception {
		stubAuthenticatedUser();
		when(journalService.getSellJournal(eq(USER_ID), eq(SELL_TRADE_ID)))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).getSellJournal(USER_ID, SELL_TRADE_ID);
	}

	@Test
	void getSellJournalReturnsBadRequestWhenServiceRejectsNonSellTrade() throws Exception {
		stubAuthenticatedUser();
		when(journalService.getSellJournal(eq(USER_ID), eq(SELL_TRADE_ID)))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		mockMvc.perform(get("/api/journal/sell/{sellTradeId}", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).getSellJournal(USER_ID, SELL_TRADE_ID);
	}
}
