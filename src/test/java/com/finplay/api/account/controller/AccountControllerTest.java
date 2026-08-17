// 계좌 요약 조회 API의 인증, 검증, 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.account.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.account.domain.Market;
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
class AccountControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AccountService accountService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void getAccountSummaryReturnsOkWithEveryFieldWhenMarketIsStock() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		AccountSummaryResponse response = AccountSummaryResponse.of(
			9_000_000L, 500_000L, 1_200_000L, 10_200_000L, 50_000L, 200_000L);
		when(accountService.getAccountSummary(USER_ID, Market.STOCK)).thenReturn(response);

		mockMvc.perform(get("/api/accounts/summary")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cashBalance").value(9000000))
			.andExpect(jsonPath("$.reservedCash").value(500000))
			.andExpect(jsonPath("$.holdingsValue").value(1200000))
			.andExpect(jsonPath("$.totalValue").value(10200000))
			.andExpect(jsonPath("$.realizedPnl").value(50000))
			.andExpect(jsonPath("$.unrealizedPnl").value(200000));

		verify(accountService).getAccountSummary(USER_ID, Market.STOCK);
	}

	@Test
	void getAccountSummaryReturnsOkWithEveryFieldWhenMarketIsCrypto() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		AccountSummaryResponse response = AccountSummaryResponse.of(
			10_000_000L, 0L, 0L, 10_000_000L, 0L, 0L);
		when(accountService.getAccountSummary(USER_ID, Market.CRYPTO)).thenReturn(response);

		mockMvc.perform(get("/api/accounts/summary")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cashBalance").value(10000000))
			.andExpect(jsonPath("$.reservedCash").value(0))
			.andExpect(jsonPath("$.holdingsValue").value(0))
			.andExpect(jsonPath("$.totalValue").value(10000000))
			.andExpect(jsonPath("$.realizedPnl").value(0))
			.andExpect(jsonPath("$.unrealizedPnl").value(0));

		verify(accountService).getAccountSummary(USER_ID, Market.CRYPTO);
	}

	@Test
	void getAccountSummaryRejectsMissingMarketWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(get("/api/accounts/summary")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(accountService);
	}

	@Test
	void getAccountSummaryRejectsInvalidMarketLiteralWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(get("/api/accounts/summary")
			.param("market", "FOREX")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(accountService);
	}

	@Test
	void getAccountSummaryRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/accounts/summary")
			.param("market", "STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(accountService);
	}
}
