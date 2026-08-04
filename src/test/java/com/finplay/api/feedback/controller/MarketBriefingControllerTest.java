// 개장 전 브리핑 조회 API의 인증·market 파라미터 검증·직렬화·오류 매핑 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.feedback.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.feedback.domain.FeedbackContentStatus;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.feedback.service.MarketBriefingService;
import com.finplay.api.market.domain.Market;
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

// 계약의 정본은 docs/api-contracts.md의 "개장 전 브리핑 조회" 행이고, 상태값은 spec 012 §C-4다.
// 게이트·범위·상한은 MarketBriefingQueryGateIntegrationTest가, 판정 순서는 MarketBriefingServiceTest가 맡는다.
//
// market 400은 컨트롤러에 검증 코드가 없고 GlobalExceptionHandler에 맡겨져 있다 — 그래서 "핸들러가 실제로
// 그 예외를 400 VALIDATION_ERROR로 매핑하는가"가 여기서만 확인된다. 서비스 시그니처가 Market이라
// 컴파일만으로는 아무것도 보장되지 않는다.
@WebMvcTest(MarketBriefingController.class)
@Import(SecurityConfig.class)
class MarketBriefingControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 1L;
	private static final String PATH = "/api/market/briefing";

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 7, 28);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MarketBriefingService marketBriefingService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	// --- 인증 (배정 건수에 넣지 않지만 이 엔드포인트에도 적용된다) ---

	@Test
	@DisplayName("토큰 없이 호출하면 401이고 서비스를 부르지 않는다")
	void rejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get(PATH).param("market", "STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(marketBriefingService);
	}

	@Test
	@DisplayName("잘못된 Bearer 토큰이면 401이고 서비스를 부르지 않는다")
	void rejectsInvalidBearerTokenWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken("not.a.jwt")).thenReturn(Optional.empty());

		mockMvc.perform(get(PATH).param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(marketBriefingService);
	}

	// --- API 계약 market이 없거나 허용 값 밖이면 400 (완료 조건) ---

	@Test
	@DisplayName("market 파라미터가 없으면 400 VALIDATION_ERROR이고 서비스를 부르지 않는다")
	void rejectsMissingMarketParameterWithBadRequest() throws Exception {
		authenticate();

		mockMvc.perform(authorized(get(PATH)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(marketBriefingService);
	}

	@Test
	@DisplayName("허용 값 밖의 market이면 400 VALIDATION_ERROR이고 서비스를 부르지 않는다")
	void rejectsUnknownMarketValueWithBadRequest() throws Exception {
		authenticate();

		mockMvc.perform(authorized(get(PATH)).param("market", "FOREX"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(marketBriefingService);
	}

	// 스프링의 문자열→열거형 변환은 대소문자를 구분한다. 관대해지는 회귀(대소문자 무시 변환기 추가 등)가
	// 들어오면 계약이 조용히 넓어지므로 여기서 고정한다.
	@Test
	@DisplayName("소문자 stock도 400이다 — 열거형 리터럴만 허용한다")
	void rejectsLowercaseMarketValueWithBadRequest() throws Exception {
		authenticate();

		mockMvc.perform(authorized(get(PATH)).param("market", "stock"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(marketBriefingService);
	}

	@Test
	@DisplayName("빈 문자열 market도 400이다")
	void rejectsBlankMarketValueWithBadRequest() throws Exception {
		authenticate();

		mockMvc.perform(authorized(get(PATH)).param("market", ""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(marketBriefingService);
	}

	@Test
	@DisplayName("CRYPTO는 허용 값이라 400이 아니고 그대로 서비스에 전달된다")
	void acceptsCryptoAsAValidMarket() throws Exception {
		authenticate();
		when(marketBriefingService.getBriefing(Market.CRYPTO)).thenReturn(
			MarketBriefingResponse.withoutItems(Market.CRYPTO, null, FeedbackContentStatus.EMPTY));

		mockMvc.perform(authorized(get(PATH)).param("market", "CRYPTO"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("CRYPTO"));

		verify(marketBriefingService).getBriefing(Market.CRYPTO);
	}

	// --- 응답 형태 ---

	// Part D 항목은 NewsItem의 다섯 값에 종목 3종을 평평하게 더한 여덟 값이다 (§C-6). 중첩으로 감싸면
	// JSON 모양이 계약과 달라지므로 필드 개수까지 단정한다.
	@Test
	@DisplayName("READY 응답이 계약대로 직렬화된다 — items 항목이 평평한 여덟 값이다")
	void serializesReadyResponseAccordingToTheContract() throws Exception {
		authenticate();
		when(marketBriefingService.getBriefing(Market.STOCK)).thenReturn(
			MarketBriefingResponse.of(
				Market.STOCK,
				ORIGIN_TRADE_DATE,
				FeedbackContentStatus.READY,
				"간밤 반도체 업황을 다룬 기사가 있었습니다.",
				List.of(newsItem(), disclosureItem())));

		mockMvc.perform(authorized(get(PATH)).param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.originTradeDate").value("2026-07-29"))
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.summary").isNotEmpty())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].instrumentId").value(7))
			.andExpect(jsonPath("$.items[0].symbol").value("005930"))
			.andExpect(jsonPath("$.items[0].name").value("삼성전자"))
			.andExpect(jsonPath("$.items[0].type").value("NEWS"))
			.andExpect(jsonPath("$.items[0].title").value("반도체 업황 둔화 우려 확산"))
			.andExpect(jsonPath("$.items[0].publisher").value("hankyung.com"))
			.andExpect(jsonPath("$.items[0].url").value("https://news.example.test/1"))
			.andExpect(jsonPath("$.items[0].publishedAt").value("2026-07-28T18:40:00"))
			.andExpect(jsonPath("$.items[0].length()").value(8))
			// 종목을 중첩 필드로 감싸는 회귀는 이 단정에서 걸린다.
			.andExpect(jsonPath("$.items[0].instrument").doesNotExist())
			.andExpect(jsonPath("$.items[1].type").value("DISCLOSURE"))
			.andExpect(jsonPath("$.items[1].publisher").value("DART"))
			.andExpect(jsonPath("$.items[1].publishedAt").value("2026-07-28T00:00:00"));

		verify(marketBriefingService).getBriefing(Market.STOCK);
	}

	@Test
	@DisplayName("items 항목에 본문·요약 필드가 없다")
	void neverExposesArticleBodyOrSnippet() throws Exception {
		authenticate();
		when(marketBriefingService.getBriefing(Market.STOCK)).thenReturn(
			MarketBriefingResponse.of(
				Market.STOCK, ORIGIN_TRADE_DATE, FeedbackContentStatus.READY, "요약", List.of(newsItem())));

		mockMvc.perform(authorized(get(PATH)).param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].content").doesNotExist())
			.andExpect(jsonPath("$.items[0].description").doesNotExist())
			.andExpect(jsonPath("$.items[0].body").doesNotExist())
			.andExpect(jsonPath("$.narrativeSource").doesNotExist())
			.andExpect(jsonPath("$.generatedAt").doesNotExist());
	}

	// --- 상태값별 200 (FEED-009 — 비어 있는 것은 오류가 아니다) ---

	// Part C는 같은 상황에서 NOT_YET이다. 두 API가 갈리는 유일한 자리라 계약 층에서도 고정한다.
	@Test
	@DisplayName("재생세션 미준비면 EMPTY이고 originTradeDate가 null이며 200이다")
	void returnsOkWithEmptyAndNullTradeDateWhenTheSessionIsNotReady() throws Exception {
		authenticate();
		when(marketBriefingService.getBriefing(Market.STOCK)).thenReturn(
			MarketBriefingResponse.withoutItems(Market.STOCK, null, FeedbackContentStatus.EMPTY));

		mockMvc.perform(authorized(get(PATH)).param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("EMPTY"))
			.andExpect(jsonPath("$.originTradeDate").doesNotExist())
			.andExpect(jsonPath("$.summary").doesNotExist())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	@DisplayName("개장 전이면 NOT_YET이고 originTradeDate는 채워지며 200이다")
	void returnsOkWithNotYetAndFilledTradeDateBeforeMarketOpen() throws Exception {
		authenticate();
		when(marketBriefingService.getBriefing(Market.STOCK)).thenReturn(
			MarketBriefingResponse.withoutItems(
				Market.STOCK, ORIGIN_TRADE_DATE, FeedbackContentStatus.NOT_YET));

		mockMvc.perform(authorized(get(PATH)).param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("NOT_YET"))
			.andExpect(jsonPath("$.originTradeDate").value("2026-07-29"))
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	@DisplayName("UNAVAILABLE이면 summary는 null이지만 items는 채워지고 200이다")
	void returnsOkWithFilledItemsWhenTheNarrativeIsUnavailable() throws Exception {
		authenticate();
		when(marketBriefingService.getBriefing(Market.STOCK)).thenReturn(
			MarketBriefingResponse.of(
				Market.STOCK, ORIGIN_TRADE_DATE, FeedbackContentStatus.UNAVAILABLE, null, List.of(newsItem())));

		mockMvc.perform(authorized(get(PATH)).param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.summary").doesNotExist())
			.andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	@DisplayName("서비스가 어떤 상태값을 주든 200이다 — 오류 코드로 바뀌지 않는다")
	void neverTurnsAnEmptyBriefingIntoAnError() throws Exception {
		authenticate();
		when(marketBriefingService.getBriefing(any())).thenReturn(
			MarketBriefingResponse.of(
				Market.STOCK, ORIGIN_TRADE_DATE, FeedbackContentStatus.EMPTY, null, List.of(newsItem())));

		mockMvc.perform(authorized(get(PATH)).param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("EMPTY"))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.error").doesNotExist());
	}

	private static BriefingNewsItem newsItem() {
		return new BriefingNewsItem(
			7L,
			"005930",
			"삼성전자",
			MarketNewsItemType.NEWS,
			"반도체 업황 둔화 우려 확산",
			"hankyung.com",
			"https://news.example.test/1",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 40)));
	}

	private static BriefingNewsItem disclosureItem() {
		return new BriefingNewsItem(
			8L,
			"000660",
			"SK하이닉스",
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
