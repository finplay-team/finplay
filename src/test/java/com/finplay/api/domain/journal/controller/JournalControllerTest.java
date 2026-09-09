package com.finplay.api.domain.journal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.journal.dto.response.BuyJournalResponse;
import com.finplay.api.domain.journal.dto.response.BuyJournalUpdateResponse;
import com.finplay.api.domain.journal.dto.response.SellJournalResponse;
import com.finplay.api.domain.journal.dto.response.SellJournalUpdateResponse;
import com.finplay.api.domain.journal.service.JournalService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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

@WebMvcTest(JournalController.class)
@Import(SecurityConfig.class)
class JournalControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;
	private static final long BUY_TRADE_ID = 12L;
	private static final long SELL_TRADE_ID = 34L;
	private static final String VALID_BODY = """
		{"content":"실적 발표 전 분할 매수. 5% 빠지면 손절 계획."}
		""";
	private static final String VALID_SELL_BODY = """
		{"content":"목표가 도달해 전량 매도. 다음엔 좀 더 분할로 팔아보자."}
		""";
	private static final String VALID_UPDATE_BODY = """
		{"content":"돌아보니 목표가 도달 전에 일부 익절했어야 했다."}
		""";
	private static final String VALID_BUY_UPDATE_BODY = """
		{"content":"돌아보니 분할 매수 비중을 더 늘렸어야 했다."}
		""";

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
	void createBuyJournalReturnsCreatedWithEveryResponseField() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime createdAt = LocalDateTime.of(2026, 8, 4, 10, 12, 33);
		BuyJournalResponse response = new BuyJournalResponse(
			1L, BUY_TRADE_ID, "실적 발표 전 분할 매수. 5% 빠지면 손절 계획.", createdAt);
		when(journalService.createBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any()))
			.thenReturn(response);

		mockMvc.perform(post("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.journalId").value(1))
			.andExpect(jsonPath("$.buyTradeId").value(BUY_TRADE_ID))
			.andExpect(jsonPath("$.content").value("실적 발표 전 분할 매수. 5% 빠지면 손절 계획."))
			.andExpect(jsonPath("$.createdAt").value("2026-08-04T10:12:33"));

		verify(journalService).createBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any());
	}

	@Test
	void createBuyJournalRejectsBlankContentWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"content":"   "}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void createBuyJournalRejectsMissingContentWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void createBuyJournalRejectsContentOverMaxLengthWithoutCallingService() throws Exception {
		stubAuthenticatedUser();
		String overLimitContent = "a".repeat(5001);

		mockMvc.perform(post("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"" + overLimitContent + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void createBuyJournalRejectsNonNumericBuyTradeIdWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/trades/{buyTradeId}/journal", "abc")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void createBuyJournalRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void createBuyJournalReturnsForbiddenWhenServiceRejectsOwnership() throws Exception {
		stubAuthenticatedUser();
		when(journalService.createBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		mockMvc.perform(post("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).createBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any());
	}

	@Test
	void createBuyJournalReturnsNotFoundWhenServiceRejectsMissingTrade() throws Exception {
		stubAuthenticatedUser();
		when(journalService.createBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(post("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).createBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any());
	}

	@Test
	void createBuyJournalReturnsConflictWhenServiceRejectsDuplicate() throws Exception {
		stubAuthenticatedUser();
		when(journalService.createBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.DUPLICATE_RESOURCE));

		mockMvc.perform(post("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).createBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any());
	}

	@Test
	void createBuyJournalReturnsBadRequestWhenServiceRejectsNonBuyTrade() throws Exception {
		stubAuthenticatedUser();
		when(journalService.createBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		mockMvc.perform(post("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).createBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any());
	}

	@Test
	void createSellJournalReturnsCreatedWithEveryResponseField() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime createdAt = LocalDateTime.of(2026, 8, 4, 11, 3, 21);
		SellJournalResponse response = new SellJournalResponse(
			2L, SELL_TRADE_ID, "목표가 도달해 전량 매도. 다음엔 좀 더 분할로 팔아보자.", createdAt);
		when(journalService.createSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any()))
			.thenReturn(response);

		mockMvc.perform(post("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_SELL_BODY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.journalId").value(2))
			.andExpect(jsonPath("$.sellTradeId").value(SELL_TRADE_ID))
			.andExpect(jsonPath("$.content").value("목표가 도달해 전량 매도. 다음엔 좀 더 분할로 팔아보자."))
			.andExpect(jsonPath("$.createdAt").value("2026-08-04T11:03:21"));

		verify(journalService).createSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any());
	}

	@Test
	void createSellJournalRejectsBlankContentWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"content":"   "}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void createSellJournalRejectsMissingContentWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void createSellJournalRejectsContentOverMaxLengthWithoutCallingService() throws Exception {
		stubAuthenticatedUser();
		String overLimitContent = "a".repeat(5001);

		mockMvc.perform(post("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"" + overLimitContent + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void createSellJournalRejectsNonNumericSellTradeIdWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/trades/{sellTradeId}/sell-journal", "abc")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_SELL_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void createSellJournalRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_SELL_BODY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void createSellJournalReturnsForbiddenWhenServiceRejectsOwnership() throws Exception {
		stubAuthenticatedUser();
		when(journalService.createSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		mockMvc.perform(post("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_SELL_BODY))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).createSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any());
	}

	@Test
	void createSellJournalReturnsNotFoundWhenServiceRejectsMissingTrade() throws Exception {
		stubAuthenticatedUser();
		when(journalService.createSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(post("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_SELL_BODY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).createSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any());
	}

	@Test
	void createSellJournalReturnsConflictWhenServiceRejectsDuplicate() throws Exception {
		stubAuthenticatedUser();
		when(journalService.createSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.DUPLICATE_RESOURCE));

		mockMvc.perform(post("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_SELL_BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).createSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any());
	}

	@Test
	void createSellJournalReturnsBadRequestWhenServiceRejectsNonSellTrade() throws Exception {
		stubAuthenticatedUser();
		when(journalService.createSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		mockMvc.perform(post("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_SELL_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).createSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any());
	}

	@Test
	void updateSellJournalReturnsOkWithEveryResponseField() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime createdAt = LocalDateTime.of(2026, 8, 4, 15, 20, 41);
		LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 5, 9, 3, 12);
		SellJournalUpdateResponse response = new SellJournalUpdateResponse(
			2L, SELL_TRADE_ID, "돌아보니 목표가 도달 전에 일부 익절했어야 했다.", createdAt, updatedAt);
		when(journalService.updateSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any()))
			.thenReturn(response);

		mockMvc.perform(patch("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_UPDATE_BODY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.journalId").value(2))
			.andExpect(jsonPath("$.sellTradeId").value(SELL_TRADE_ID))
			.andExpect(jsonPath("$.content").value("돌아보니 목표가 도달 전에 일부 익절했어야 했다."))
			.andExpect(jsonPath("$.createdAt").value("2026-08-04T15:20:41"))
			.andExpect(jsonPath("$.updatedAt").value("2026-08-05T09:03:12"));

		verify(journalService).updateSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any());
	}

	@Test
	void updateSellJournalRejectsBlankContentWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(patch("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"content":"   "}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void updateSellJournalRejectsMissingContentWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(patch("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void updateSellJournalRejectsContentOverMaxLengthWithoutCallingService() throws Exception {
		stubAuthenticatedUser();
		String overLimitContent = "a".repeat(5001);

		mockMvc.perform(patch("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"" + overLimitContent + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void updateSellJournalRejectsNonNumericSellTradeIdWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(patch("/api/trades/{sellTradeId}/sell-journal", "abc")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_UPDATE_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void updateSellJournalRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(patch("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_UPDATE_BODY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void updateSellJournalReturnsForbiddenWhenServiceRejectsOwnership() throws Exception {
		stubAuthenticatedUser();
		when(journalService.updateSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		mockMvc.perform(patch("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_UPDATE_BODY))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).updateSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any());
	}

	@Test
	void updateSellJournalReturnsNotFoundWhenServiceRejectsMissingTrade() throws Exception {
		stubAuthenticatedUser();
		when(journalService.updateSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(patch("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_UPDATE_BODY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).updateSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any());
	}

	@Test
	void updateSellJournalReturnsNotFoundWhenServiceRejectsMissingJournal() throws Exception {
		stubAuthenticatedUser();
		when(journalService.updateSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(patch("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_UPDATE_BODY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).updateSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any());
	}

	@Test
	void updateSellJournalReturnsBadRequestWhenServiceRejectsNonSellTrade() throws Exception {
		stubAuthenticatedUser();
		when(journalService.updateSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		mockMvc.perform(patch("/api/trades/{sellTradeId}/sell-journal", SELL_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_UPDATE_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).updateSellJournal(eq(USER_ID), eq(SELL_TRADE_ID), any());
	}

	@Test
	void updateBuyJournalReturnsOkWithEveryResponseField() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime createdAt = LocalDateTime.of(2026, 8, 4, 10, 12, 33);
		LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 5, 8, 47, 5);
		BuyJournalUpdateResponse response = new BuyJournalUpdateResponse(
			1L, BUY_TRADE_ID, "돌아보니 분할 매수 비중을 더 늘렸어야 했다.", createdAt, updatedAt);
		when(journalService.updateBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any()))
			.thenReturn(response);

		mockMvc.perform(patch("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BUY_UPDATE_BODY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.journalId").value(1))
			.andExpect(jsonPath("$.buyTradeId").value(BUY_TRADE_ID))
			.andExpect(jsonPath("$.content").value("돌아보니 분할 매수 비중을 더 늘렸어야 했다."))
			.andExpect(jsonPath("$.createdAt").value("2026-08-04T10:12:33"))
			.andExpect(jsonPath("$.updatedAt").value("2026-08-05T08:47:05"));

		verify(journalService).updateBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any());
	}

	@Test
	void updateBuyJournalRejectsBlankContentWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(patch("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"content":"   "}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void updateBuyJournalRejectsMissingContentWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(patch("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void updateBuyJournalRejectsContentOverMaxLengthWithoutCallingService() throws Exception {
		stubAuthenticatedUser();
		String overLimitContent = "a".repeat(5001);

		mockMvc.perform(patch("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"content\":\"" + overLimitContent + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void updateBuyJournalRejectsNonNumericBuyTradeIdWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(patch("/api/trades/{buyTradeId}/journal", "abc")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BUY_UPDATE_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void updateBuyJournalRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(patch("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BUY_UPDATE_BODY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(journalService);
	}

	@Test
	void updateBuyJournalReturnsForbiddenWhenServiceRejectsOwnership() throws Exception {
		stubAuthenticatedUser();
		when(journalService.updateBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		mockMvc.perform(patch("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BUY_UPDATE_BODY))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).updateBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any());
	}

	@Test
	void updateBuyJournalReturnsNotFoundWhenServiceRejectsMissingTrade() throws Exception {
		stubAuthenticatedUser();
		when(journalService.updateBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(patch("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BUY_UPDATE_BODY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).updateBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any());
	}

	@Test
	void updateBuyJournalReturnsNotFoundWhenServiceRejectsMissingJournal() throws Exception {
		stubAuthenticatedUser();
		when(journalService.updateBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(patch("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BUY_UPDATE_BODY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).updateBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any());
	}

	@Test
	void updateBuyJournalReturnsBadRequestWhenServiceRejectsNonBuyTrade() throws Exception {
		stubAuthenticatedUser();
		when(journalService.updateBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		mockMvc.perform(patch("/api/trades/{buyTradeId}/journal", BUY_TRADE_ID)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BUY_UPDATE_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(journalService).updateBuyJournal(eq(USER_ID), eq(BUY_TRADE_ID), any());
	}
}
