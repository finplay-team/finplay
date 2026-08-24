// 시장별 보유 종목 목록 조회 API의 인증, 검증, 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.domain.portfolio.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.portfolio.dto.response.HoldingListItemResponse;
import com.finplay.api.domain.portfolio.service.HoldingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HoldingController.class)
@Import(SecurityConfig.class)
class HoldingControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private HoldingService holdingService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void getHoldingsReturnsOkWithEveryFieldWhenMarketIsStock() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		HoldingListItemResponse holding = new HoldingListItemResponse(
			101L,
			1L,
			"005930",
			"삼성전자",
			new BigDecimal("10"),
			new BigDecimal("3"),
			new BigDecimal("70000"),
			new BigDecimal("75000"),
			750_000L,
			50_000L,
			new BigDecimal("0.0714"),
			"AVAILABLE");
		when(holdingService.getHoldings(USER_ID, Market.STOCK)).thenReturn(List.of(holding));

		mockMvc.perform(get("/api/holdings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].holdingId").value(101))
			.andExpect(jsonPath("$[0].instrumentId").value(1))
			.andExpect(jsonPath("$[0].symbol").value("005930"))
			.andExpect(jsonPath("$[0].name").value("삼성전자"))
			.andExpect(jsonPath("$[0].quantity").value(10))
			.andExpect(jsonPath("$[0].reservedQuantity").value(3))
			.andExpect(jsonPath("$[0].averagePrice").value(70000))
			.andExpect(jsonPath("$[0].currentPrice").value(75000))
			.andExpect(jsonPath("$[0].evaluationAmount").value(750000))
			.andExpect(jsonPath("$[0].unrealizedPnl").value(50000))
			.andExpect(jsonPath("$[0].returnRate").value(0.0714))
			.andExpect(jsonPath("$[0].priceStatus").value("AVAILABLE"));

		verify(holdingService).getHoldings(USER_ID, Market.STOCK);
	}

	@Test
	void getHoldingsReturnsOkWithEveryFieldWhenMarketIsCrypto() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		HoldingListItemResponse holding = new HoldingListItemResponse(
			102L,
			2L,
			"BTC",
			"비트코인",
			new BigDecimal("0.5"),
			new BigDecimal("0.1"),
			new BigDecimal("50000000"),
			null,
			null,
			null,
			null,
			"UNAVAILABLE");
		when(holdingService.getHoldings(USER_ID, Market.CRYPTO)).thenReturn(List.of(holding));

		mockMvc.perform(get("/api/holdings")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].holdingId").value(102))
			.andExpect(jsonPath("$[0].instrumentId").value(2))
			.andExpect(jsonPath("$[0].symbol").value("BTC"))
			.andExpect(jsonPath("$[0].name").value("비트코인"))
			.andExpect(jsonPath("$[0].quantity").value(0.5))
			.andExpect(jsonPath("$[0].reservedQuantity").value(0.1))
			.andExpect(jsonPath("$[0].averagePrice").value(50000000))
			// PR #97 리뷰 권장사항 2: doesNotExist()는 값이 null이어도 통과해 "필드 자체가 없음"과
			// "필드가 null로 존재함"을 구분하지 못한다. value(nullValue())는 필드가 없으면 PathNotFound로
			// 실패해 "필드가 존재하고 값이 null"이라는 계약을 실제로 고정한다.
			.andExpect(jsonPath("$[0].currentPrice").value(nullValue()))
			.andExpect(jsonPath("$[0].evaluationAmount").value(nullValue()))
			.andExpect(jsonPath("$[0].unrealizedPnl").value(nullValue()))
			.andExpect(jsonPath("$[0].returnRate").value(nullValue()))
			.andExpect(jsonPath("$[0].priceStatus").value("UNAVAILABLE"));

		verify(holdingService).getHoldings(USER_ID, Market.CRYPTO);
	}

	@Test
	void getHoldingsReturnsOkWithEmptyArrayWhenNoHoldings() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(holdingService.getHoldings(USER_ID, Market.STOCK)).thenReturn(List.of());

		mockMvc.perform(get("/api/holdings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$").isArray())
			.andExpect(jsonPath("$").isEmpty());

		verify(holdingService).getHoldings(USER_ID, Market.STOCK);
	}

	@Test
	void getHoldingsRejectsMissingMarketWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(get("/api/holdings")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(holdingService);
	}

	@Test
	void getHoldingsRejectsInvalidMarketLiteralWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(get("/api/holdings")
			.param("market", "FOREX")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(holdingService);
	}

	@Test
	void getHoldingsRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/holdings")
			.param("market", "STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(holdingService);
	}
}
