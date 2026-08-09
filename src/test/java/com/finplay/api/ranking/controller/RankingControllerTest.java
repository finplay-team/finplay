// 전체 랭킹 조회 API의 인증, 검증, 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.ranking.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.ranking.domain.RankingStatus;
import com.finplay.api.ranking.dto.response.MyRankingResponse;
import com.finplay.api.ranking.dto.response.RankingListItemResponse;
import com.finplay.api.ranking.dto.response.RankingListResponse;
import com.finplay.api.ranking.service.RankingService;
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

	// 이슈 #279의 직렬화 계약을 따로 못박는다. 위 테스트의 jsonPath(...).value("READY")는 값만 보므로,
	// 두 가지가 확인되지 않은 채 남는다.
	//
	// 1) enum이 **문자열로** 나가는지. RankingStatus에 @JsonValue가 붙거나 전역 Jackson 설정이
	//    WRITE_ENUMS_USING_INDEX로 바뀌면 응답이 0/1이 되어 클라이언트 파싱이 통째로 깨지는데,
	//    그건 자바 타입 시그니처에는 드러나지 않는다. isString()으로 타입 자체를 고정한다.
	// 2) status가 **wrapper에만** 있고 항목에는 없는지. api-contracts.md가 "항목은 rank·nickname·realizedPnl
	//    3개 필드로 고정"이라고 못박은 부분이라, 항목에 status가 새로 새어 나오면 계약 위반이다.
	//
	// 기존 필드가 이름·타입 그대로인지도 여기서 함께 본다(하위 호환 — 필드 추가만 있었다는 확인).
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
			// 항목에는 status가 없어야 한다 — 응답 전체의 성질이라 wrapper에만 둔다는 계약이다.
			.andExpect(jsonPath("$.content[0].status").doesNotExist())
			.andExpect(jsonPath("$.content[0].rank").isNumber())
			.andExpect(jsonPath("$.content[0].nickname").isString())
			.andExpect(jsonPath("$.content[0].realizedPnl").isNumber());
	}

	// 내 랭킹도 같은 직렬화 계약을 따른다. rank는 null일 때 필드가 사라지는(doesNotExist) 기존 동작이
	// status 추가 이후에도 그대로인지 함께 확인한다 — 하위 호환의 실체다.
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

	// 이슈 #279: 유실 상태여도 오류가 아니라 200 + status로 알린다. 기존 필드(market·content)도 그대로다.
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

	// limit=0은 400이 아니라 그대로 서비스에 전달된다 — 서비스가 10으로 클램핑한다(GET /api/trades와의 차이).
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

	// limit=-1도 400이 아니라 그대로 서비스에 전달된다 — 서비스가 10으로 클램핑한다.
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

	// limit=51도 400이 아니라 그대로 서비스에 전달된다 — 서비스가 50으로 클램핑한다.
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

	// RANK-002: 매도 이력이 없으면 rank가 null인 200 응답 필드 계약을 검증한다 — 오류가 아니다.
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

	// 이슈 #279: rank가 null인 같은 형태의 응답이라도 status가 REBUILDING이면 "매도 이력 없음"이 아니라
	// "집계 준비 중"이다. 이 구별이 클라이언트가 판별 가능해야 하는 지점이다(위 READY 케이스와 짝).
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

	// RANK-002: 매도 이력이 있으면 rank·nickname·realizedPnl·market 필드 계약을 정상 값으로 검증한다.
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
