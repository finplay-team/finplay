// 매수 투자일기 작성 API의 인증, 검증, 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.journal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.finplay.api.journal.dto.response.BuyJournalResponse;
import com.finplay.api.journal.service.JournalService;
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
	private static final String VALID_BODY = """
		{"content":"실적 발표 전 분할 매수. 5% 빠지면 손절 계획."}
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
}
