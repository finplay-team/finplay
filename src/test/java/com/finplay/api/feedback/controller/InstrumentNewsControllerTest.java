// 종목 뉴스 목록·요약 조회 API의 인증·직렬화·오류 매핑 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.feedback.controller;

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
import com.finplay.api.feedback.domain.FeedbackContentStatus;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.feedback.dto.response.NewsItem;
import com.finplay.api.feedback.service.InstrumentNewsQueryService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// 계약의 정본은 docs/api-contracts.md의 "종목 뉴스 목록·요약 조회" 행이고, 상태값은 spec 012 §C-4다.
// 게이트·범위·상태값 판정이 실제 데이터 위에서 성립하는지는 InstrumentNewsQueryGateIntegrationTest가,
// 판정 순서 자체는 InstrumentNewsQueryServiceTest가 맡는다 — 여기서는 직렬화 형태와 인증·오류 매핑만 본다.
@WebMvcTest(InstrumentNewsController.class)
@Import(SecurityConfig.class)
class InstrumentNewsControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 1L;
	private static final long INSTRUMENT_ID = 1L;
	private static final String PATH = "/api/instruments/{instrumentId}/news";

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 7, 28);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private InstrumentNewsQueryService instrumentNewsQueryService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	// --- API 계약 ① 인증 없이 호출하면 401 ---

	@Test
	@DisplayName("토큰 없이 호출하면 401이고 서비스를 부르지 않는다")
	void rejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get(PATH, INSTRUMENT_ID))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(instrumentNewsQueryService);
	}

	@Test
	@DisplayName("잘못된 Bearer 토큰이면 401이고 서비스를 부르지 않는다")
	void rejectsInvalidBearerTokenWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken("not.a.jwt")).thenReturn(Optional.empty());

		mockMvc.perform(get(PATH, INSTRUMENT_ID).header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(instrumentNewsQueryService);
	}

	// --- 응답 형태 ---

	@Test
	@DisplayName("READY 응답이 계약대로 직렬화된다 — 다섯 값의 items와 요약 문장")
	void serializesReadyResponseAccordingToTheContract() throws Exception {
		authenticate();
		when(instrumentNewsQueryService.getInstrumentNews(INSTRUMENT_ID)).thenReturn(
			InstrumentNewsResponse.of(
				ORIGIN_TRADE_DATE,
				NewsSummaryScope.PRE_MARKET,
				FeedbackContentStatus.READY,
				"직전 거래일 장 마감 이후 반도체 업황을 다룬 기사가 있었습니다.",
				List.of(newsItem(), disclosureItem())));

		mockMvc.perform(authorized(get(PATH, INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.originTradeDate").value("2026-07-29"))
			.andExpect(jsonPath("$.summaryScope").value("PRE_MARKET"))
			.andExpect(jsonPath("$.summaryStatus").value("READY"))
			.andExpect(jsonPath("$.summary").isNotEmpty())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].type").value("NEWS"))
			.andExpect(jsonPath("$.items[0].title").value("반도체 업황 둔화 우려 확산"))
			.andExpect(jsonPath("$.items[0].publisher").value("hankyung.com"))
			.andExpect(jsonPath("$.items[0].url").value("https://news.example.test/1"))
			.andExpect(jsonPath("$.items[0].publishedAt").value("2026-07-28T18:40:00"))
			// 공시는 published_at이 접수일 00:00:00이다 (§C-3·§C-8).
			.andExpect(jsonPath("$.items[1].type").value("DISCLOSURE"))
			.andExpect(jsonPath("$.items[1].publisher").value("DART"))
			.andExpect(jsonPath("$.items[1].publishedAt").value("2026-07-28T00:00:00"));

		verify(instrumentNewsQueryService).getInstrumentNews(INSTRUMENT_ID);
	}

	// §정책 전제(저작권) — 항목은 제목·언론사·원문 URL·발행시각·종류까지만 나간다.
	@Test
	@DisplayName("items 항목에 본문·요약 필드가 없다")
	void neverExposesArticleBodyOrSnippet() throws Exception {
		authenticate();
		when(instrumentNewsQueryService.getInstrumentNews(INSTRUMENT_ID)).thenReturn(
			InstrumentNewsResponse.of(
				ORIGIN_TRADE_DATE, NewsSummaryScope.FULL, FeedbackContentStatus.READY, "요약", List.of(newsItem())));

		mockMvc.perform(authorized(get(PATH, INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].content").doesNotExist())
			.andExpect(jsonPath("$.items[0].description").doesNotExist())
			.andExpect(jsonPath("$.items[0].body").doesNotExist())
			.andExpect(jsonPath("$.items[0].length()").value(5))
			// 상태 판정에 쓰는 내부 값이 새면 클라이언트가 아직 열리지 않은 기사의 존재를 역산할 수 있다.
			.andExpect(jsonPath("$.narrativeSource").doesNotExist())
			.andExpect(jsonPath("$.generatedAt").doesNotExist());
	}

	// --- 상태값별 200 (FEED-008 — 비어 있는 것은 오류가 아니다) ---

	@Test
	@DisplayName("개장 전이면 NOT_YET이고 originTradeDate는 채워지며 200이다")
	void returnsOkWithNotYetAndFilledTradeDateBeforeMarketOpen() throws Exception {
		authenticate();
		when(instrumentNewsQueryService.getInstrumentNews(INSTRUMENT_ID))
			.thenReturn(InstrumentNewsResponse.notYet(ORIGIN_TRADE_DATE));

		mockMvc.perform(authorized(get(PATH, INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summaryStatus").value("NOT_YET"))
			.andExpect(jsonPath("$.originTradeDate").value("2026-07-29"))
			.andExpect(jsonPath("$.summaryScope").doesNotExist())
			.andExpect(jsonPath("$.summary").doesNotExist())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	// 재생세션 미준비는 어떤 거래일을 재생 중인지 자체가 확정되지 않아 날짜까지 null이다 (§C-4 1번).
	@Test
	@DisplayName("재생세션 미준비면 originTradeDate까지 null이고 200이다")
	void returnsOkWithNullTradeDateWhenTheReplaySessionIsNotReady() throws Exception {
		authenticate();
		when(instrumentNewsQueryService.getInstrumentNews(INSTRUMENT_ID))
			.thenReturn(InstrumentNewsResponse.notYet(null));

		mockMvc.perform(authorized(get(PATH, INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summaryStatus").value("NOT_YET"))
			.andExpect(jsonPath("$.originTradeDate").doesNotExist())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	@DisplayName("기사가 0건이면 EMPTY이고 items는 빈 배열이며 200이다")
	void returnsOkWithEmptyItemsWhenThereIsNoArticle() throws Exception {
		authenticate();
		when(instrumentNewsQueryService.getInstrumentNews(INSTRUMENT_ID)).thenReturn(
			InstrumentNewsResponse.of(
				ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET, FeedbackContentStatus.EMPTY, null, List.of()));

		mockMvc.perform(authorized(get(PATH, INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summaryStatus").value("EMPTY"))
			.andExpect(jsonPath("$.summaryScope").value("PRE_MARKET"))
			.andExpect(jsonPath("$.summary").doesNotExist())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	// 상태값 ④ — 행은 있는데 서술이 없다. EMPTY와 달리 items가 채워지는 것이 이 상태의 표식이다.
	@Test
	@DisplayName("UNAVAILABLE이면 summary는 null이지만 items는 채워지고 200이다")
	void returnsOkWithFilledItemsWhenTheNarrativeIsUnavailable() throws Exception {
		authenticate();
		when(instrumentNewsQueryService.getInstrumentNews(INSTRUMENT_ID)).thenReturn(
			InstrumentNewsResponse.of(
				ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET, FeedbackContentStatus.UNAVAILABLE, null,
				List.of(newsItem())));

		mockMvc.perform(authorized(get(PATH, INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.summaryStatus").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.summary").doesNotExist())
			.andExpect(jsonPath("$.items.length()").value(1));
	}

	// --- 오류 매핑 ---

	@Test
	@DisplayName("없는 종목이면 404 NOT_FOUND 공통 오류 형식이다")
	void mapsMissingInstrumentToNotFound() throws Exception {
		authenticate();
		when(instrumentNewsQueryService.getInstrumentNews(999L))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(authorized(get(PATH, 999L)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("instrumentId가 숫자가 아니면 서비스를 부르지 않는다")
	void rejectsNonNumericInstrumentIdWithoutCallingService() throws Exception {
		authenticate();

		mockMvc.perform(authorized(get("/api/instruments/abc/news")))
			.andExpect(status().is4xxClientError());

		verifyNoInteractions(instrumentNewsQueryService);
	}

	private static NewsItem newsItem() {
		return new NewsItem(
			MarketNewsItemType.NEWS,
			"반도체 업황 둔화 우려 확산",
			"hankyung.com",
			"https://news.example.test/1",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 40)));
	}

	private static NewsItem disclosureItem() {
		return new NewsItem(
			MarketNewsItemType.DISCLOSURE,
			"주요사항보고서(유상증자결정)",
			"DART",
			"https://dart.fss.or.kr/report/1",
			PREVIOUS_TRADE_DATE.atStartOfDay());
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN);
	}
}
