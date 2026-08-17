// 전체 포트폴리오 합산 요약 조회 API의 인증, 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.portfolio.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.portfolio.dto.response.PortfolioSummaryResponse;
import com.finplay.api.portfolio.service.PortfolioService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PortfolioController.class)
@Import(SecurityConfig.class)
class PortfolioControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PortfolioService portfolioService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void getPortfolioSummaryReturnsOkWithEveryField() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		PortfolioSummaryResponse response = PortfolioSummaryResponse.of(20_200_000L, 200_000L, 50_000L);
		when(portfolioService.getPortfolioSummary(USER_ID)).thenReturn(response);

		mockMvc.perform(get("/api/portfolio")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalValue").value(20200000))
			.andExpect(jsonPath("$.unrealizedPnl").value(200000))
			.andExpect(jsonPath("$.realizedPnl").value(50000));

		verify(portfolioService).getPortfolioSummary(USER_ID);
	}

	@Test
	void getPortfolioSummaryRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/portfolio"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(portfolioService);
	}
}
