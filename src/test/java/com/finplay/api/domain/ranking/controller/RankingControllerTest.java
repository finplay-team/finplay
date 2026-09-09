package com.finplay.api.domain.ranking.controller;

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
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.ranking.dto.response.MyRankingResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListItemResponse;
import com.finplay.api.domain.ranking.dto.response.RankingListResponse;
import com.finplay.api.domain.ranking.entity.RankingStatus;
import com.finplay.api.domain.ranking.service.RankingService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RankingController.class)
@Import(SecurityConfig.class)
class RankingControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RankingService rankingService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	private void stubAuthenticatedUser() {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	@Test
	void getRankingsReturnsOkWithEveryResponseField() throws Exception {
		stubAuthenticatedUser();
		RankingListResponse response = new RankingListResponse(
			"STOCK",
			RankingStatus.READY,
			List.of(
				new RankingListItemResponse(1, "투자왕", 500_000L),
				new RankingListItemResponse(1, "차트요정", 500_000L),
				new RankingListItemResponse(3, "존버맨", 120_000L)));
		when(rankingService.getRankings(Market.STOCK, null)).thenReturn(response);

		mockMvc.perform(get("/api/rankings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.content[0].rank").value(1))
			.andExpect(jsonPath("$.content[0].nickname").value("투자왕"))
			.andExpect(jsonPath("$.content[0].realizedPnl").value(500000))
			.andExpect(jsonPath("$.content[1].rank").value(1))
			.andExpect(jsonPath("$.content[2].rank").value(3));

		verify(rankingService).getRankings(Market.STOCK, null);
	}

	@Test
	void getRankingsSerializesStatusAsStringOnTheWrapperOnly() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getRankings(Market.STOCK, null)).thenReturn(new RankingListResponse(
			"STOCK", RankingStatus.READY, List.of(new RankingListItemResponse(1, "투자왕", 500_000L))));

		mockMvc.perform(get("/api/rankings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").isString())
			.andExpect(jsonPath("$.market").isString())
			.andExpect(jsonPath("$.content").isArray())
			.andExpect(jsonPath("$.content[0].status").doesNotExist())
			.andExpect(jsonPath("$.content[0].rank").isNumber())
			.andExpect(jsonPath("$.content[0].nickname").isString())
			.andExpect(jsonPath("$.content[0].realizedPnl").isNumber());
	}

	@Test
	void getMyRankingSerializesStatusAsStringAndKeepsLegacyFieldTypes() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getMyRanking(USER_ID, Market.CRYPTO))
			.thenReturn(new MyRankingResponse("CRYPTO", RankingStatus.READY, 3, "존버맨", 120_000L));

		mockMvc.perform(get("/api/rankings/me")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").isString())
			.andExpect(jsonPath("$.market").isString())
			.andExpect(jsonPath("$.rank").isNumber())
			.andExpect(jsonPath("$.nickname").isString())
			.andExpect(jsonPath("$.realizedPnl").isNumber());
	}

	@Test
	void getRankingsReturnsOkWithEmptyContentWhenMarketIsCrypto() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getRankings(Market.CRYPTO, null))
			.thenReturn(new RankingListResponse("CRYPTO", RankingStatus.READY, List.of()));

		mockMvc.perform(get("/api/rankings")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.content").isEmpty());

		verify(rankingService).getRankings(Market.CRYPTO, null);
	}

	@Test
	void getRankingsReturnsOkWithRebuildingStatusWhenAggregationIsLost() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getRankings(Market.STOCK, null))
			.thenReturn(new RankingListResponse("STOCK", RankingStatus.REBUILDING, List.of()));

		mockMvc.perform(get("/api/rankings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.status").value("REBUILDING"))
			.andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	void getRankingsReturnsOkWithUnavailableStatusWhenRedisConnectionFails() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getRankings(Market.STOCK, null))
			.thenReturn(new RankingListResponse("STOCK", RankingStatus.UNAVAILABLE, List.of()));

		mockMvc.perform(get("/api/rankings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	void getRankingsPassesLimitToServiceWhenProvided() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getRankings(eq(Market.STOCK), eq(20)))
			.thenReturn(new RankingListResponse("STOCK", RankingStatus.READY, List.of()));

		mockMvc.perform(get("/api/rankings")
			.param("market", "STOCK")
			.param("limit", "20")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(rankingService).getRankings(Market.STOCK, 20);
	}

	@Test
	void getRankingsPassesNullLimitToServiceWhenOmitted() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getRankings(eq(Market.STOCK), isNull()))
			.thenReturn(new RankingListResponse("STOCK", RankingStatus.READY, List.of()));

		mockMvc.perform(get("/api/rankings")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(rankingService).getRankings(Market.STOCK, null);
	}

	@Test
	void getRankingsReturnsOkAndPassesZeroLimitToServiceWithoutRejecting() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getRankings(eq(Market.STOCK), eq(0)))
			.thenReturn(new RankingListResponse("STOCK", RankingStatus.READY, List.of()));

		mockMvc.perform(get("/api/rankings")
			.param("market", "STOCK")
			.param("limit", "0")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(rankingService).getRankings(Market.STOCK, 0);
	}

	@Test
	void getRankingsReturnsOkAndPassesNegativeLimitToServiceWithoutRejecting() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getRankings(eq(Market.STOCK), eq(-1)))
			.thenReturn(new RankingListResponse("STOCK", RankingStatus.READY, List.of()));

		mockMvc.perform(get("/api/rankings")
			.param("market", "STOCK")
			.param("limit", "-1")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(rankingService).getRankings(Market.STOCK, -1);
	}

	@Test
	void getRankingsReturnsOkAndPassesLimitAboveMaximumToServiceWithoutRejecting() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getRankings(eq(Market.STOCK), eq(51)))
			.thenReturn(new RankingListResponse("STOCK", RankingStatus.READY, List.of()));

		mockMvc.perform(get("/api/rankings")
			.param("market", "STOCK")
			.param("limit", "51")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(rankingService).getRankings(Market.STOCK, 51);
	}

	@Test
	void getRankingsRejectsMissingMarketWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/rankings")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(rankingService);
	}

	@Test
	void getRankingsRejectsUnsupportedMarketLiteralWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/rankings")
			.param("market", "FOREX")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(rankingService);
	}

	@Test
	void getRankingsRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/rankings")
			.param("market", "STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(rankingService);
	}

	@Test
	void getMyRankingReturnsOkWithNullRankWhenNoSellHistory() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getMyRanking(USER_ID, Market.STOCK))
			.thenReturn(new MyRankingResponse("STOCK", RankingStatus.READY, null, "투자왕", 0L));

		mockMvc.perform(get("/api/rankings/me")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.rank").doesNotExist())
			.andExpect(jsonPath("$.nickname").value("투자왕"))
			.andExpect(jsonPath("$.realizedPnl").value(0));

		verify(rankingService).getMyRanking(USER_ID, Market.STOCK);
	}

	@Test
	void getMyRankingReturnsOkWithRebuildingStatusWhenAggregationIsLost() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getMyRanking(USER_ID, Market.STOCK))
			.thenReturn(new MyRankingResponse("STOCK", RankingStatus.REBUILDING, null, "투자왕", 0L));

		mockMvc.perform(get("/api/rankings/me")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.status").value("REBUILDING"))
			.andExpect(jsonPath("$.rank").doesNotExist())
			.andExpect(jsonPath("$.nickname").value("투자왕"));
	}

	@Test
	void getMyRankingReturnsOkWithEveryResponseFieldWhenSellHistoryExists() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getMyRanking(USER_ID, Market.CRYPTO))
			.thenReturn(new MyRankingResponse("CRYPTO", RankingStatus.READY, 3, "존버맨", 120_000L));

		mockMvc.perform(get("/api/rankings/me")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.rank").value(3))
			.andExpect(jsonPath("$.nickname").value("존버맨"))
			.andExpect(jsonPath("$.realizedPnl").value(120000));

		verify(rankingService).getMyRanking(USER_ID, Market.CRYPTO);
	}

	@Test
	void getMyRankingReturnsOkWithUnavailableStatusWhenRedisConnectionFails() throws Exception {
		stubAuthenticatedUser();
		when(rankingService.getMyRanking(USER_ID, Market.STOCK))
			.thenReturn(new MyRankingResponse("STOCK", RankingStatus.UNAVAILABLE, null, "투자왕", 0L));

		mockMvc.perform(get("/api/rankings/me")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.rank").doesNotExist())
			.andExpect(jsonPath("$.nickname").value("투자왕"));
	}

	@Test
	void getMyRankingRejectsMissingMarketWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/rankings/me")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(rankingService);
	}

	@Test
	void getMyRankingRejectsUnsupportedMarketLiteralWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/rankings/me")
			.param("market", "FOREX")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(rankingService);
	}

	@Test
	void getMyRankingRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/rankings/me")
			.param("market", "STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(rankingService);
	}
}
