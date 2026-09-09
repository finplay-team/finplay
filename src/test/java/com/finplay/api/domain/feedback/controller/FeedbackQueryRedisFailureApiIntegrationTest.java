package com.finplay.api.domain.feedback.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.domain.feedback.repository.MarketBriefingRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import({TestcontainersConfiguration.class,
	TestClockConfig.class})
@TestPropertySource(properties = "feedback.query-cache.enabled=true")
class FeedbackQueryRedisFailureApiIntegrationTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final LocalDateTime NOW = LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 0));

	private static final String NEWS_PATH = "/api/instruments/{instrumentId}/news";

	private static final String BRIEFING_PATH = "/api/market/briefing";

	private static final String STOCK_SYMBOL = "005930";

	private static final String ACCESS_TOKEN = "access-token";

	private static final String SUMMARY_TEXT = "직전 거래일 장 마감 이후 기사가 이어졌습니다.";

	private static final String BRIEFING_TEXT = "간밤 기사가 이어졌습니다.";

	private static final String NEWS_TITLE = "전일 저녁 기사";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	@Autowired
	private MarketBriefingRepository marketBriefingRepository;

	@Autowired
	private TestClock clock;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	private Instrument stock;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(1L, "USER")));

		stock = instrumentService.getInstrumentEntities(Market.STOCK).stream()
			.filter(each -> STOCK_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();
		stockReplaySessionRepository.save(StockReplaySession.ready(
			SERVICE_DATE,
			ORIGIN_TRADE_DATE,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 40)),
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 0))));
		marketNewsItemRepository.save(MarketNewsItem.create(
			stock, MarketNewsItemType.NEWS, NEWS_TITLE, "테스트경제",
			"https://news.example.test/redis-down/1",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)), NOW));
		instrumentNewsSummaryRepository.save(InstrumentNewsSummary.create(
			stock, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET, SUMMARY_TEXT, NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 45))));
		marketBriefingRepository.save(MarketBriefing.create(
			Market.STOCK, ORIGIN_TRADE_DATE, BRIEFING_TEXT, NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 45))));
	}

	private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN);
	}

	@Test
	@DisplayName("Redis가 죽어 있어도 종목 뉴스·요약 조회가 200과 정상 본문(READY·서술·items)을 낸다")
	void instrumentNewsQueryStaysTwoHundredWithAFullBodyWhenRedisIsDown() throws Exception {
		mockMvc.perform(authorized(get(NEWS_PATH, stock.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.originTradeDate").value(ORIGIN_TRADE_DATE.toString()))
			.andExpect(jsonPath("$.summaryScope").value("PRE_MARKET"))
			.andExpect(jsonPath("$.summaryStatus").value("READY"))
			.andExpect(jsonPath("$.summary").value(SUMMARY_TEXT))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].title").value(NEWS_TITLE));
	}

	@Test
	@DisplayName("Redis가 죽어 있어도 개장 전 브리핑 조회가 200과 정상 본문(READY·서술·items)을 낸다")
	void marketBriefingQueryStaysTwoHundredWithAFullBodyWhenRedisIsDown() throws Exception {
		mockMvc.perform(authorized(get(BRIEFING_PATH)).param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.originTradeDate").value(ORIGIN_TRADE_DATE.toString()))
			.andExpect(jsonPath("$.status").value("READY"))
			.andExpect(jsonPath("$.summary").value(BRIEFING_TEXT))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].title").value(NEWS_TITLE));
	}

	@Test
	@DisplayName("Redis가 죽은 채로 같은 조회를 반복해도 계속 200이다")
	void repeatedQueriesKeepReturningTwoHundredWhileRedisStaysDown() throws Exception {
		for (int attempt = 0; attempt < 3; attempt++) {
			mockMvc.perform(authorized(get(NEWS_PATH, stock.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summaryStatus").value("READY"));
			mockMvc.perform(authorized(get(BRIEFING_PATH)).param("market", "STOCK"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("READY"));
		}
	}

}
