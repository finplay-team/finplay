package com.finplay.api.domain.feedback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.PriceMoveListResponse;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.service.PriceMoveQueryService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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

@WebMvcTest(PriceMoveController.class)
@Import(SecurityConfig.class)
class PriceMoveControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 1L;
	private static final long INSTRUMENT_ID = 1L;
	private static final String PATH = "/api/instruments/{instrumentId}/price-moves";

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PriceMoveQueryService priceMoveQueryService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	@DisplayName("토큰 없이 호출하면 401이고 서비스를 부르지 않는다")
	void rejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get(PATH, INSTRUMENT_ID))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(priceMoveQueryService);
	}

	@Test
	@DisplayName("잘못된 Bearer 토큰이면 401이고 서비스를 부르지 않는다")
	void rejectsInvalidBearerTokenWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken("not.a.jwt")).thenReturn(Optional.empty());

		mockMvc.perform(get(PATH, INSTRUMENT_ID).header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(priceMoveQueryService);
	}

	@Test
	@DisplayName("카드가 0건이면 originTradeDate는 있고 moves는 빈 배열이며 200이다")
	void returnsOkWithEmptyMovesWhenThereIsNoCard() throws Exception {
		authenticate();
		when(priceMoveQueryService.getPriceMoves(INSTRUMENT_ID))
			.thenReturn(PriceMoveListResponse.of(ORIGIN_TRADE_DATE, List.of()));

		mockMvc.perform(authorized(get(PATH, INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.originTradeDate").value("2026-07-29"))
			.andExpect(jsonPath("$.status").value("EMPTY"))
			.andExpect(jsonPath("$.moves").isArray())
			.andExpect(jsonPath("$.moves.length()").value(0));
	}

	@Test
	@DisplayName("originTradeDate가 없으면 키는 남고 값만 null로 직렬화되며 200이다")
	void returnsOkWithNullOriginTradeDateWhenSessionIsNotReady() throws Exception {
		authenticate();
		when(priceMoveQueryService.getPriceMoves(INSTRUMENT_ID))
			.thenReturn(PriceMoveListResponse.notYet());

		mockMvc.perform(authorized(get(PATH, INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("\"originTradeDate\":null")))
			.andExpect(jsonPath("$.status").value("NOT_YET"))
			.andExpect(jsonPath("$.moves").isArray())
			.andExpect(jsonPath("$.moves.length()").value(0));
	}

	@Test
	@DisplayName("재생세션 미준비(주식)와 카드 0건(코인)의 JSON이 status로 갈린다")
	void distinguishesNotYetFromEmptyInSerializedJson() throws Exception {
		authenticate();
		when(priceMoveQueryService.getPriceMoves(INSTRUMENT_ID))
			.thenReturn(PriceMoveListResponse.notYet());
		when(priceMoveQueryService.getPriceMoves(CRYPTO_INSTRUMENT_ID))
			.thenReturn(PriceMoveListResponse.of(null, List.of()));

		String notYetJson = mockMvc.perform(authorized(get(PATH, INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String emptyJson = mockMvc.perform(authorized(get(PATH, CRYPTO_INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(notYetJson).isNotEqualTo(emptyJson);
		assertThat(notYetJson).contains("\"status\":\"NOT_YET\"");
		assertThat(emptyJson).contains("\"status\":\"EMPTY\"");
	}

	private static final long CRYPTO_INSTRUMENT_ID = 2L;

	@Test
	@DisplayName("코인 instrumentId로 호출해 카드가 0건이면 originTradeDate=null·moves=[]이고 200이다")
	void returnsNullOriginTradeDateAndEmptyMovesForCryptoInstrumentWithNoCards() throws Exception {
		authenticate();
		when(priceMoveQueryService.getPriceMoves(CRYPTO_INSTRUMENT_ID))
			.thenReturn(PriceMoveListResponse.of(null, List.of()));

		mockMvc.perform(authorized(get(PATH, CRYPTO_INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("\"originTradeDate\":null")))
			.andExpect(jsonPath("$.status").value("EMPTY"))
			.andExpect(jsonPath("$.moves").isArray())
			.andExpect(jsonPath("$.moves.length()").value(0));

		verify(priceMoveQueryService).getPriceMoves(CRYPTO_INSTRUMENT_ID);
	}

	@Test
	@DisplayName("코인 instrumentId로 호출해 카드가 있으면 originTradeDate=null이고 카드는 계약대로 직렬화된다")
	void returnsNullOriginTradeDateWithSerializedCardsForCryptoInstrument() throws Exception {
		authenticate();
		LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 5, 14, 30, 0);
		PriceMoveItem cryptoCard = new PriceMoveItem(
			99L,
			PriceMoveEventType.INTRADAY,
			occurredAt.minusMinutes(5),
			occurredAt,
			new BigDecimal("0.031000"),
			"5분간 3.1% 상승했습니다.",
			List.of(new NewsItem(
				MarketNewsItemType.NEWS,
				"대형 거래소 상장",
				"coindesk.com",
				"https://news.example.test/crypto/1",
				occurredAt.minusMinutes(10))));
		when(priceMoveQueryService.getPriceMoves(CRYPTO_INSTRUMENT_ID))
			.thenReturn(PriceMoveListResponse.of(null, List.of(cryptoCard)));

		mockMvc.perform(authorized(get(PATH, CRYPTO_INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("\"originTradeDate\":null")))
			.andExpect(jsonPath("$.moves.length()").value(1))
			.andExpect(jsonPath("$.moves[0].windowStart").value("2026-08-05T14:25:00"))
			.andExpect(jsonPath("$.moves[0].windowEnd").value("2026-08-05T14:30:00"))
			.andExpect(jsonPath("$.moves[0].sources[0].title").value("대형 거래소 상장"));
	}

	@Test
	@DisplayName("카드 응답이 계약대로 직렬화된다 — 구간에 날짜가 붙고 근거는 네 값뿐이다")
	void serializesCardAccordingToTheContract() throws Exception {
		authenticate();
		when(priceMoveQueryService.getPriceMoves(INSTRUMENT_ID))
			.thenReturn(PriceMoveListResponse.of(ORIGIN_TRADE_DATE, List.of(sampleCard())));

		mockMvc.perform(authorized(get(PATH, INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.moves.length()").value(1))
			.andExpect(jsonPath("$.moves[0].id").value(12))
			.andExpect(jsonPath("$.moves[0].eventType").value("INTRADAY"))
			.andExpect(jsonPath("$.moves[0].windowStart").value("2026-07-29T11:20:00"))
			.andExpect(jsonPath("$.moves[0].windowEnd").value("2026-07-29T11:25:00"))
			.andExpect(jsonPath("$.moves[0].changeRate").value(-0.018200))
			.andExpect(jsonPath("$.moves[0].narrative").isNotEmpty())
			.andExpect(jsonPath("$.moves[0].sources.length()").value(1))
			.andExpect(jsonPath("$.moves[0].sources[0].type").value("NEWS"))
			.andExpect(jsonPath("$.moves[0].sources[0].title").value("생산 차질"))
			.andExpect(jsonPath("$.moves[0].sources[0].publisher").value("hankyung.com"))
			.andExpect(jsonPath("$.moves[0].sources[0].url").value("https://news.example.test/1"))
			.andExpect(jsonPath("$.moves[0].sources[0].publishedAt").value("2026-07-29T11:15:00"));

		verify(priceMoveQueryService).getPriceMoves(INSTRUMENT_ID);
	}

	@Test
	@DisplayName("응답에 revealTime 필드가 없다")
	void neverExposesRevealTime() throws Exception {
		authenticate();
		when(priceMoveQueryService.getPriceMoves(INSTRUMENT_ID))
			.thenReturn(PriceMoveListResponse.of(ORIGIN_TRADE_DATE, List.of(sampleCard())));

		mockMvc.perform(authorized(get(PATH, INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.moves[0].revealTime").doesNotExist())
			.andExpect(jsonPath("$.moves[0].detectionScore").doesNotExist())
			.andExpect(jsonPath("$.moves[0].narrativeSource").doesNotExist());
	}

	@Test
	@DisplayName("근거에 본문·요약 필드가 없다")
	void neverExposesArticleBodyOrSnippet() throws Exception {
		authenticate();
		when(priceMoveQueryService.getPriceMoves(INSTRUMENT_ID))
			.thenReturn(PriceMoveListResponse.of(ORIGIN_TRADE_DATE, List.of(sampleCard())));

		mockMvc.perform(authorized(get(PATH, INSTRUMENT_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.moves[0].sources[0].content").doesNotExist())
			.andExpect(jsonPath("$.moves[0].sources[0].description").doesNotExist())
			.andExpect(jsonPath("$.moves[0].sources[0].summary").doesNotExist())
			.andExpect(jsonPath("$.moves[0].sources[0].body").doesNotExist())
			.andExpect(jsonPath("$.moves[0].sources[0].length()").value(5));
	}

	@Test
	@DisplayName("없는 종목이면 404 NOT_FOUND 공통 오류 형식이다")
	void mapsMissingInstrumentToNotFound() throws Exception {
		authenticate();
		when(priceMoveQueryService.getPriceMoves(999L))
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

		mockMvc.perform(authorized(get("/api/instruments/abc/price-moves")))
			.andExpect(status().is4xxClientError());

		verifyNoInteractions(priceMoveQueryService);
	}

	private static PriceMoveItem sampleCard() {
		return new PriceMoveItem(
			12L,
			PriceMoveEventType.INTRADAY,
			LocalDateTime.of(ORIGIN_TRADE_DATE, java.time.LocalTime.of(11, 20)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, java.time.LocalTime.of(11, 25)),
			new BigDecimal("-0.018200"),
			"11시 20분부터 5분간 1.82% 하락했습니다.",
			List.of(new NewsItem(
				MarketNewsItemType.NEWS,
				"생산 차질",
				"hankyung.com",
				"https://news.example.test/1",
				LocalDateTime.of(ORIGIN_TRADE_DATE, java.time.LocalTime.of(11, 15)))));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN);
	}
}
