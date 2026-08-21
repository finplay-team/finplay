// 체결 내역 조회 API의 인증, 검증, 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.domain.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.order.dto.response.TradeListItemResponse;
import com.finplay.api.domain.order.dto.response.TradeListResponse;
import com.finplay.api.domain.order.service.TradeService;
import java.math.BigDecimal;
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

@WebMvcTest(TradeController.class)
@Import(SecurityConfig.class)
class TradeControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TradeService tradeService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	private void stubAuthenticatedUser() {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	@Test
	void getMyTradesReturnsOkWithEveryFieldWhenMarketIsStock() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime executedAt = LocalDateTime.of(2026, 7, 29, 9, 0);
		TradeListItemResponse buy = new TradeListItemResponse(
			1L, 1L, "BUY", new BigDecimal("70000"), new BigDecimal("10"), 700_000L, 105L, null, executedAt);
		TradeListItemResponse sell = new TradeListItemResponse(
			2L, 1L, "SELL", new BigDecimal("71000"), new BigDecimal("5"), 355_000L, 53L, 5_000L, executedAt);
		TradeListResponse response = TradeListResponse.of(List.of(sell, buy), "2026-07-29T09:00:00_1", true);
		when(tradeService.getMyTrades(USER_ID, Market.STOCK, null, 20)).thenReturn(response);

		mockMvc.perform(get("/api/trades")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].tradeId").value(2))
			.andExpect(jsonPath("$.content[0].instrumentId").value(1))
			.andExpect(jsonPath("$.content[0].side").value("SELL"))
			.andExpect(jsonPath("$.content[0].price").value(71000))
			.andExpect(jsonPath("$.content[0].quantity").value(5))
			.andExpect(jsonPath("$.content[0].amount").value(355000))
			.andExpect(jsonPath("$.content[0].fee").value(53))
			.andExpect(jsonPath("$.content[0].realizedPnl").value(5000))
			.andExpect(jsonPath("$.content[0].executedAt").value("2026-07-29T09:00:00"))
			.andExpect(jsonPath("$.content[0].orderId").doesNotExist())
			.andExpect(jsonPath("$.content[0].market").doesNotExist())
			.andExpect(jsonPath("$.content[0].orderType").doesNotExist())
			.andExpect(jsonPath("$.content[0].status").doesNotExist())
			.andExpect(jsonPath("$.content[0].requestedAt").doesNotExist())
			.andExpect(jsonPath("$.content[1].tradeId").value(1))
			.andExpect(jsonPath("$.content[1].realizedPnl").doesNotExist())
			.andExpect(jsonPath("$.nextCursor").value("2026-07-29T09:00:00_1"))
			.andExpect(jsonPath("$.hasNext").value(true));

		verify(tradeService).getMyTrades(USER_ID, Market.STOCK, null, 20);
	}

	@Test
	void getMyTradesReturnsOkWithEmptyContentWhenMarketIsCrypto() throws Exception {
		stubAuthenticatedUser();
		TradeListResponse response = TradeListResponse.of(List.of(), null, false);
		when(tradeService.getMyTrades(USER_ID, Market.CRYPTO, null, 20)).thenReturn(response);

		mockMvc.perform(get("/api/trades")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isEmpty())
			.andExpect(jsonPath("$.nextCursor").doesNotExist())
			.andExpect(jsonPath("$.hasNext").value(false));

		verify(tradeService).getMyTrades(USER_ID, Market.CRYPTO, null, 20);
	}

	@Test
	void getMyTradesUsesDefaultLimitWhenLimitIsOmitted() throws Exception {
		stubAuthenticatedUser();
		when(tradeService.getMyTrades(eq(USER_ID), eq(Market.STOCK), isNull(), eq(20)))
			.thenReturn(TradeListResponse.of(List.of(), null, false));

		mockMvc.perform(get("/api/trades")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(tradeService).getMyTrades(USER_ID, Market.STOCK, null, 20);
	}

	@Test
	void getMyTradesPassesCursorAndLimitToService() throws Exception {
		stubAuthenticatedUser();
		when(tradeService.getMyTrades(eq(USER_ID), eq(Market.STOCK), any(), eq(10)))
			.thenReturn(TradeListResponse.of(List.of(), null, false));

		mockMvc.perform(get("/api/trades")
			.param("market", "STOCK")
			.param("cursor", "2026-07-29T09:00:00_1")
			.param("limit", "10")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(tradeService).getMyTrades(USER_ID, Market.STOCK, "2026-07-29T09:00:00_1", 10);
	}

	@Test
	void getMyTradesRejectsMissingMarketWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/trades")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(tradeService);
	}

	@Test
	void getMyTradesRejectsInvalidMarketLiteralWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/trades")
			.param("market", "FOREX")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(tradeService);
	}

	@Test
	void getMyTradesRejectsLimitBelowMinimumWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/trades")
			.param("market", "STOCK")
			.param("limit", "0")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(tradeService);
	}

	@Test
	void getMyTradesRejectsLimitAboveMaximumWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/trades")
			.param("market", "STOCK")
			.param("limit", "101")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(tradeService);
	}

	@Test
	void getMyTradesReturnsBadRequestWhenServiceRejectsMalformedCursor() throws Exception {
		stubAuthenticatedUser();
		when(tradeService.getMyTrades(eq(USER_ID), eq(Market.STOCK), eq("garbage"), eq(20)))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다."));

		mockMvc.perform(get("/api/trades")
			.param("market", "STOCK")
			.param("cursor", "garbage")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(tradeService).getMyTrades(USER_ID, Market.STOCK, "garbage", 20);
	}

	@Test
	void getMyTradesRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/trades")
			.param("market", "STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(tradeService);
	}
}
