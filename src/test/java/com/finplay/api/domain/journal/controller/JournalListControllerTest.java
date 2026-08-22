// 투자일기 목록 조회 API의 인증, 검증, 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.domain.journal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.journal.dto.response.JournalListItemResponse;
import com.finplay.api.domain.journal.dto.response.JournalListResponse;
import com.finplay.api.domain.journal.service.JournalService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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

@WebMvcTest(JournalListController.class)
@Import(SecurityConfig.class)
class JournalListControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;

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
	void getMyJournalEntriesReturnsOkWithEverySixFieldsAndNoJournalIdWhenMarketIsStock() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime sellCreatedAt = LocalDateTime.of(2026, 8, 4, 15, 20, 41);
		LocalDateTime sellUpdatedAt = LocalDateTime.of(2026, 8, 4, 15, 20, 41);
		LocalDateTime buyCreatedAt = LocalDateTime.of(2026, 8, 4, 10, 12, 33);
		LocalDateTime buyUpdatedAt = LocalDateTime.of(2026, 8, 5, 9, 3, 12);
		JournalListItemResponse sellItem = new JournalListItemResponse(
			"SELL", null, 34L, "목표가 도달해서 전량 매도.", sellCreatedAt, sellUpdatedAt);
		JournalListItemResponse buyItem = new JournalListItemResponse(
			"BUY", 12L, null, "실적 발표 전 분할 매수.", buyCreatedAt, buyUpdatedAt);
		JournalListResponse response = JournalListResponse.of(
			List.of(sellItem, buyItem), "2026-08-04T10:12:33_12", true);
		when(journalService.getMyJournalEntries(USER_ID, Market.STOCK, null, 20)).thenReturn(response);

		mockMvc.perform(get("/api/journal")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].journalType").value("SELL"))
			.andExpect(jsonPath("$.content[0].buyTradeId").doesNotExist())
			.andExpect(jsonPath("$.content[0].sellTradeId").value(34))
			.andExpect(jsonPath("$.content[0].content").value("목표가 도달해서 전량 매도."))
			.andExpect(jsonPath("$.content[0].createdAt").value("2026-08-04T15:20:41"))
			.andExpect(jsonPath("$.content[0].updatedAt").value("2026-08-04T15:20:41"))
			.andExpect(jsonPath("$.content[0].journalId").doesNotExist())
			.andExpect(jsonPath("$.content[1].journalType").value("BUY"))
			.andExpect(jsonPath("$.content[1].buyTradeId").value(12))
			.andExpect(jsonPath("$.content[1].sellTradeId").doesNotExist())
			.andExpect(jsonPath("$.content[1].content").value("실적 발표 전 분할 매수."))
			.andExpect(jsonPath("$.content[1].createdAt").value("2026-08-04T10:12:33"))
			.andExpect(jsonPath("$.content[1].updatedAt").value("2026-08-05T09:03:12"))
			.andExpect(jsonPath("$.content[1].journalId").doesNotExist())
			.andExpect(jsonPath("$.nextCursor").value("2026-08-04T10:12:33_12"))
			.andExpect(jsonPath("$.hasNext").value(true));

		verify(journalService).getMyJournalEntries(USER_ID, Market.STOCK, null, 20);
	}

	@Test
	void getMyJournalEntriesReturnsOkWithEmptyContentWhenMarketIsCrypto() throws Exception {
		stubAuthenticatedUser();
		JournalListResponse response = JournalListResponse.of(List.of(), null, false);
		when(journalService.getMyJournalEntries(USER_ID, Market.CRYPTO, null, 20)).thenReturn(response);

		mockMvc.perform(get("/api/journal")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isEmpty())
			.andExpect(jsonPath("$.nextCursor").doesNotExist())
			.andExpect(jsonPath("$.hasNext").value(false));

		verify(journalService).getMyJournalEntries(USER_ID, Market.CRYPTO, null, 20);
	}

	@Test
	void getMyJournalEntriesUsesDefaultLimitWhenLimitIsOmitted() throws Exception {
		stubAuthenticatedUser();
		when(journalService.getMyJournalEntries(eq(USER_ID), eq(Market.STOCK), isNull(), eq(20)))
			.thenReturn(JournalListResponse.of(List.of(), null, false));

		mockMvc.perform(get("/api/journal")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(journalService).getMyJournalEntries(USER_ID, Market.STOCK, null, 20);
	}

	@Test
	void getMyJournalEntriesPassesCursorAndLimitToService() throws Exception {
		stubAuthenticatedUser();
		when(journalService.getMyJournalEntries(eq(USER_ID), eq(Market.STOCK), any(), eq(10)))
			.thenReturn(JournalListResponse.of(List.of(), null, false));

		mockMvc.perform(get("/api/journal")
			.param("market", "STOCK")
			.param("cursor", "2026-08-04T10:12:33_12")
			.param("limit", "10")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(journalService).getMyJournalEntries(USER_ID, Market.STOCK, "2026-08-04T10:12:33_12", 10);
	}

	@Test
	void getMyJournalEntriesRejectsMissingMarketWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/journal")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void getMyJournalEntriesRejectsInvalidMarketLiteralWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/journal")
			.param("market", "FOREX")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void getMyJournalEntriesRejectsLimitBelowMinimumWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/journal")
			.param("market", "STOCK")
			.param("limit", "0")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void getMyJournalEntriesRejectsLimitAboveMaximumWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/journal")
			.param("market", "STOCK")
			.param("limit", "101")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void getMyJournalEntriesReturnsBadRequestWhenServiceRejectsMalformedCursor() throws Exception {
		stubAuthenticatedUser();
		when(journalService.getMyJournalEntries(eq(USER_ID), eq(Market.STOCK), eq("garbage"), eq(20)))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다."));

		mockMvc.perform(get("/api/journal")
			.param("market", "STOCK")
			.param("cursor", "garbage")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).getMyJournalEntries(USER_ID, Market.STOCK, "garbage", 20);
	}

	@Test
	void getMyJournalEntriesRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/journal")
			.param("market", "STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void getMyJournalEntriesReturnsNotFoundWhenServiceRejectsMissingAccount() throws Exception {
		stubAuthenticatedUser();
		when(journalService.getMyJournalEntries(USER_ID, Market.STOCK, null, 20))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(get("/api/journal")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).getMyJournalEntries(USER_ID, Market.STOCK, null, 20);
	}
}
