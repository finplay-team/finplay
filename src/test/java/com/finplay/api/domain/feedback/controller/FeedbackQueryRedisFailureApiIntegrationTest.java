// Redis가 응답하지 못하는 상태에서 요약 조회·브리핑 조회가 HTTP 200과 정상 응답 본문을 내는지 종단(컨트롤러→서비스→캐시)으로 검증한다 (이슈 #245 완료 조건, ADR-0015 §6).
package com.finplay.api.domain.feedback.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
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

/**
 * 이슈 #245 완료 조건이 <b>"Redis가 죽어도 조회가 200이다"</b>라고 문자 그대로 요구한다. 그 문장을 그대로
 * 확인하는 자리다 — 컨트롤러부터 캐시까지 실제로 이어 붙인 뒤 Redis만 죽인다.
 *
 * <p><b>이미 있는 두 테스트와 역할이 다르다.</b> {@code InstrumentNewsControllerTest}·
 * {@code MarketBriefingControllerTest}는 {@code @WebMvcTest} 슬라이스라 서비스가 mock이고 캐시가 아예 없으며,
 * {@code FeedbackQueryCacheBoundaryIntegrationTest}는 실제로 닿지 못하는 Redis를 쓰지만 <b>서비스 레벨</b>이라
 * HTTP 상태 코드를 찍지 않는다. "정상 반환 → 200"은 앞의 것이, "예외가 새지 않는다"는 뒤의 것이 각각 보증하고
 * 그 둘을 합치면 200이 나오지만, <b>완료 조건이 요구하는 것은 합성 논증이 아니라 실제 200이다.</b>
 *
 * <p><b>Redis를 죽이는 방법으로 {@code StringRedisTemplate}을 예외를 던지는 mock으로 바꾼다.</b> 경계 테스트의
 * 닫힌 포트 방식은 별도로 조립한 캐시에만 걸 수 있어 스프링 빈을 타는 이 경로에는 쓸 수 없다. 던지는 예외는
 * 드라이버가 접속 실패에 실제로 쓰는 {@code RedisConnectionFailureException}이며, {@code FeedbackQueryCache}가
 * 이를 {@code RuntimeException}으로 삼켜 캐시 미스로 취급하는지가 검증 대상이다.
 *
 * <p><b>200만 보고 끝내지 않는다.</b> 200인데 상태값이 {@code UNAVAILABLE}이거나 {@code items}가 비면 사용자에게는
 * 그것도 장애다 — 상태값·서술·목록까지 본문 전체를 단정한다.
 */
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

	// 10:00 — 09:00 게이트는 열렸고 15:30 전이라 주식 요약 scope는 PRE_MARKET이다.
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

	// 전역 Clock 빈을 대신하는 공용 테스트 시계 (TestClockConfig). 기준 시각은 @BeforeEach에서 세운다.
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
			// 200인데 UNAVAILABLE이면 사용자에게는 그것도 장애다 — 캐시 장애는 미스일 뿐이어야 한다.
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

	// 같은 요청을 반복해도 계속 200이다 — 캐시가 비어 있으니 매번 미스이고, 그 미스가 누적돼 예외로 바뀌는
	// 경로가 없어야 한다(락 획득 실패가 쌓여 터지는 형태를 배제한다).
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
