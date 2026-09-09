package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.store.FeedbackQueryCacheTestKeys;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	TestClockConfig.class})
class InstrumentNewsQueryGateIntegrationTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final LocalDateTime INITIAL_NOW = LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 0));

	private static final String STOCK_SYMBOL = "005930";

	private static final int FIXTURE_MARGIN = 3;

	@Autowired
	private InstrumentNewsQueryService instrumentNewsQueryService;

	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	@Autowired
	private FeedbackNewsProperties properties;

	@Autowired
	private TestClock clock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private TestClock mutableClock;

	private Instrument instrument;

	@BeforeEach
	void clearQueryCache() {
		FeedbackQueryCacheTestKeys.clear(redisTemplate);
	}

	@BeforeEach
	void setUp() {
		mutableClock = clock;
		mutableClock.set(INITIAL_NOW);
		instrument = instrumentService.getInstrumentEntities(Market.STOCK).stream()
			.filter(each -> STOCK_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();
	}

	private void givenReadySession() {
		stockReplaySessionRepository.save(StockReplaySession.ready(
			SERVICE_DATE,
			ORIGIN_TRADE_DATE,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 40)),
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 0))));
	}

	private MarketNewsItem saveItem(MarketNewsItemType type, String title, LocalDateTime publishedAt) {
		return marketNewsItemRepository.save(MarketNewsItem.create(
			instrument,
			type,
			title,
			type == MarketNewsItemType.DISCLOSURE ? "DART" : "테스트경제",
			"https://news.example.test/partc/" + title,
			publishedAt,
			INITIAL_NOW));
	}

	private void saveSummary(NewsSummaryScope scope, String text) {
		instrumentNewsSummaryRepository.save(InstrumentNewsSummary.create(
			instrument,
			ORIGIN_TRADE_DATE,
			scope,
			text,
			text == null ? NarrativeSource.NONE : NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 45))));
	}

	private InstrumentNewsResponse query() {
		return instrumentNewsQueryService.getInstrumentNews(instrument.getId());
	}

	private static List<String> titles(InstrumentNewsResponse response) {
		return response.items().stream().map(NewsItem::title).toList();
	}


	@Test
	@DisplayName("items가 max-items-per-news-list로 잘리고 공시 2건은 남으며 발행시각 내림차순이다")
	void truncatesToTheConfiguredLimitKeepingDisclosuresAndSortingByPublishedAtDescending() {
		givenReadySession();
		int limit = properties.maxItemsPerNewsList();
		int newsCount = limit + FIXTURE_MARGIN;
		for (int index = 0; index < newsCount; index++) {
			saveItem(MarketNewsItemType.NEWS, "전장 뉴스 " + index,
				LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 31)).plusMinutes(index));
		}
		saveItem(MarketNewsItemType.DISCLOSURE, "D-1 공시 A", PREVIOUS_TRADE_DATE.atStartOfDay());
		saveItem(MarketNewsItemType.DISCLOSURE, "D-1 공시 B", PREVIOUS_TRADE_DATE.atStartOfDay());

		InstrumentNewsResponse response = query();

		assertThat(response.items()).hasSize(limit);
		assertThat(response.items())
			.filteredOn(item -> item.type() == MarketNewsItemType.DISCLOSURE)
			.as("공시는 목록 최하위라 규칙이 없으면 상한을 넘는 순간 항상 먼저 잘린다 (게이트 ⑫의 전제)")
			.extracting(NewsItem::title)
			.containsExactly("D-1 공시 B", "D-1 공시 A");
		assertThat(response.items())
			.extracting(NewsItem::publishedAt)
			.isSortedAccordingTo(java.util.Comparator.reverseOrder());
		assertThat(titles(response))
			.contains("전장 뉴스 " + (newsCount - 1))
			.doesNotContain("전장 뉴스 0");
	}


	@Test
	@DisplayName("전장 하한 이전과 원본 거래일 이후의 기사가 목록에 섞이지 않는다")
	void neverMixesArticlesFromOtherOriginTradeDates() {
		givenReadySession();
		saveItem(MarketNewsItemType.NEWS, "구간 안 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveItem(MarketNewsItemType.NEWS, "하한 1분 전",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 29)));
		saveItem(MarketNewsItemType.NEWS, "다음 거래일 새벽",
			LocalDateTime.of(ORIGIN_TRADE_DATE.plusDays(1), LocalTime.of(3, 0)));

		assertThat(titles(query())).containsExactly("구간 안 기사");
	}

	@Test
	@DisplayName("전장 하한 15:30 정각 기사는 목록에 들어온다")
	void includesTheArticleExactlyAtTheLowerBound() {
		givenReadySession();
		saveItem(MarketNewsItemType.NEWS, "하한 정각",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 30)));

		assertThat(titles(query())).containsExactly("하한 정각");
	}

	@Test
	@DisplayName("장 마감 이후 조회에서도 원본 거래일 15:30 이후 기사는 나오지 않는다")
	void keepsTheUpperBoundAtTheCloseWhenQueriedAfterTradingHours() {
		givenReadySession();
		saveItem(MarketNewsItemType.NEWS, "장중 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 0)));
		saveItem(MarketNewsItemType.NEWS, "마감 후 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(16, 0)));
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(18, 0)));

		assertThat(titles(query())).containsExactly("장중 기사");
	}


	@Test
	@DisplayName("재생 시각을 지난 기사만 보이고 시계를 옮기면 그 다음 기사가 열린다")
	void revealsArticlesExactlyAsTheReplayClockPassesThem() {
		givenReadySession();
		saveItem(MarketNewsItemType.NEWS, "09:58 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 58)));
		saveItem(MarketNewsItemType.NEWS, "10:02 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 2)));

		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 0)));
		assertThat(titles(query())).containsExactly("09:58 기사");

		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 2)));
		assertThat(titles(query())).containsExactly("10:02 기사", "09:58 기사");
	}

	@Test
	@DisplayName("개장 정각 조회에서 전장 기사가 전부 열려 있다")
	void opensEveryPreMarketArticleAtMarketOpen() {
		givenReadySession();
		saveItem(MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveItem(MarketNewsItemType.NEWS, "당일 새벽 기사",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(7, 0)));
		saveItem(MarketNewsItemType.DISCLOSURE, "D-1 공시", PREVIOUS_TRADE_DATE.atStartOfDay());
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 0)));

		assertThat(titles(query()))
			.containsExactly("당일 새벽 기사", "전일 저녁 기사", "D-1 공시");
	}


	@Test
	@DisplayName("장중 조회는 PRE_MARKET 문장을 주고 FULL 문장을 절대 노출하지 않는다")
	void neverExposesTheFullSummaryDuringTradingHours() {
		givenReadySession();
		saveItem(MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveSummary(NewsSummaryScope.PRE_MARKET, "개장 전까지 반도체 업황 기사가 있었습니다.");
		saveSummary(NewsSummaryScope.FULL, "오후에 급락한 뒤 낙폭을 줄였습니다.");
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(11, 0)));

		InstrumentNewsResponse response = query();

		assertThat(response.summaryScope()).isEqualTo(NewsSummaryScope.PRE_MARKET);
		assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.READY);
		assertThat(response.summary()).isEqualTo("개장 전까지 반도체 업황 기사가 있었습니다.");
	}

	@Test
	@DisplayName("장 마감 이후 재조회하면 같은 종목의 요약이 FULL로 바뀐다")
	void switchesToTheFullSummaryAfterTheClose() {
		givenReadySession();
		saveItem(MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveSummary(NewsSummaryScope.PRE_MARKET, "개장 전까지 반도체 업황 기사가 있었습니다.");
		saveSummary(NewsSummaryScope.FULL, "오후에 급락한 뒤 낙폭을 줄였습니다.");

		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(11, 0)));
		assertThat(query().summaryScope()).isEqualTo(NewsSummaryScope.PRE_MARKET);

		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(15, 30)));
		InstrumentNewsResponse afterClose = query();
		assertThat(afterClose.summaryScope()).isEqualTo(NewsSummaryScope.FULL);
		assertThat(afterClose.summary()).isEqualTo("오후에 급락한 뒤 낙폭을 줄였습니다.");
	}


	@Test
	@DisplayName("상태값 ② 재생세션이 없으면 NOT_YET이고 originTradeDate가 null이다")
	void returnsNotYetWithoutATradeDateWhenNoReplaySessionExists() {
		InstrumentNewsResponse response = query();

		assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.NOT_YET);
		assertThat(response.originTradeDate()).isNull();
		assertThat(response.items()).isEmpty();
	}

	@Test
	@DisplayName("상태값 ③ 요약 행이 없고 기사가 있으면 EMPTY이고 items는 채워진다")
	void returnsEmptyWithFilledItemsWhenTheSummaryRowIsMissing() {
		givenReadySession();
		saveItem(MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));

		InstrumentNewsResponse response = query();

		assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.EMPTY);
		assertThat(response.summary()).isNull();
		assertThat(titles(response)).containsExactly("전일 저녁 기사");
	}

	@Test
	@DisplayName("상태값 ④ 행이 있고 summary가 NULL이면 UNAVAILABLE이고 items는 채워진다")
	void returnsUnavailableWithFilledItemsWhenTheStoredSummaryIsNull() {
		givenReadySession();
		saveItem(MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveSummary(NewsSummaryScope.PRE_MARKET, null);

		InstrumentNewsResponse response = query();

		assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.UNAVAILABLE);
		assertThat(response.summary()).isNull();
		assertThat(titles(response)).containsExactly("전일 저녁 기사");
	}

}
