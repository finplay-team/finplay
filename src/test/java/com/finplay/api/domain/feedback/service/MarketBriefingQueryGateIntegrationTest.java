package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.domain.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.domain.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.repository.MarketBriefingRepository;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	TestClockConfig.class})
class MarketBriefingQueryGateIntegrationTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final LocalDateTime INITIAL_NOW = LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 0));

	private static final int FIXTURE_MARGIN = 3;

	@Autowired
	private MarketBriefingService marketBriefingService;

	@Autowired
	private InstrumentNewsQueryService instrumentNewsQueryService;

	@Autowired
	private InstrumentNewsSummaryService instrumentNewsSummaryService;

	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private MarketBriefingRepository marketBriefingRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private FeedbackNewsProperties properties;

	@MockitoBean
	private NarrativeService narrativeService;

	@Autowired
	private TestClock clock;

	private TestClock mutableClock;

	private Instrument samsung;

	private Instrument hynix;

	@BeforeEach
	void clearQueryCache() {
		FeedbackQueryCacheTestKeys.clear(redisTemplate);
	}

	@BeforeEach
	void setUp() {
		mutableClock = clock;
		mutableClock.set(INITIAL_NOW);
		samsung = stock("005930");
		hynix = stock("000660");
		when(narrativeService.resolveNewsSummaryNarrative(any()))
			.thenReturn(NarrativeResultDto.llm("전장 구간 기사가 이어졌습니다."));
		when(narrativeService.resolveMarketBriefingNarrative(any()))
			.thenReturn(NarrativeResultDto.llm("간밤 기사가 이어졌습니다."));
	}

	private Instrument stock(String symbol) {
		return instrumentService.getInstrumentEntities(Market.STOCK).stream()
			.filter(each -> symbol.equals(each.getSymbol()))
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

	private void saveItem(Instrument instrument, MarketNewsItemType type, String title, LocalDateTime publishedAt) {
		marketNewsItemRepository.save(MarketNewsItem.create(
			instrument,
			type,
			title,
			type == MarketNewsItemType.DISCLOSURE ? "DART" : "테스트경제",
			"https://news.example.test/partd/" + instrument.getSymbol() + "/" + title,
			publishedAt,
			INITIAL_NOW));
	}

	private void saveBriefingRow(String text) {
		marketBriefingRepository.save(MarketBriefing.create(
			Market.STOCK,
			ORIGIN_TRADE_DATE,
			text,
			text == null ? NarrativeSource.NONE : NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 45))));
	}

	private MarketBriefingResponse briefing() {
		return marketBriefingService.getBriefing(Market.STOCK);
	}

	private static List<String> briefingTitles(MarketBriefingResponse response) {
		return response.items().stream().map(BriefingNewsItem::title).toList();
	}

	private static List<String> newsTitles(InstrumentNewsResponse response) {
		return response.items().stream().map(NewsItem::title).toList();
	}

	private List<String> preMarketSummaryPromptTitles(Instrument instrument) {
		instrumentNewsSummaryService.generateStockSummary(
			instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);
		ArgumentCaptor<NewsSummaryPromptDto> captor = ArgumentCaptor.forClass(NewsSummaryPromptDto.class);
		org.mockito.Mockito.verify(narrativeService).resolveNewsSummaryNarrative(captor.capture());
		return captor.getValue().items().stream().map(NewsSourceDto::title).toList();
	}

	private List<String> fullSummaryPromptTitles(Instrument instrument) {
		instrumentNewsSummaryService.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL);
		ArgumentCaptor<NewsSummaryPromptDto> captor = ArgumentCaptor.forClass(NewsSummaryPromptDto.class);
		org.mockito.Mockito.verify(narrativeService).resolveNewsSummaryNarrative(captor.capture());
		return captor.getValue().items().stream().map(NewsSourceDto::title).toList();
	}

	@Test
	@DisplayName("개장 전 08:41에 두 API를 모두 호출해도 어느 쪽에서도 전장 기사가 나오지 않는다")
	void neitherApiExposesPreMarketArticlesBeforeMarketOpen() {
		givenReadySession();
		saveItem(samsung, MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveItem(samsung, MarketNewsItemType.DISCLOSURE, "D-1 접수 공시", PREVIOUS_TRADE_DATE.atStartOfDay());
		saveBriefingRow("간밤 기사가 이어졌습니다.");
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 41)));

		MarketBriefingResponse partD = briefing();
		InstrumentNewsResponse partC = instrumentNewsQueryService.getInstrumentNews(samsung.getId());

		assertThat(partD.items()).as("Part D가 09:00 전에 전장 기사를 보이면 안 된다").isEmpty();
		assertThat(partD.summary()).isNull();
		assertThat(partD.status()).isEqualTo(FeedbackContentStatus.NOT_YET);
		assertThat(partC.items()).as("Part C가 09:00 전에 전장 기사를 보이면 안 된다").isEmpty();
		assertThat(partC.summaryStatus()).isEqualTo(FeedbackContentStatus.NOT_YET);
		assertThat(partD.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
		assertThat(partC.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
	}

	@Test
	@DisplayName("개장 정각에는 두 API가 함께 열리고 같은 전장 기사를 보여준다")
	void bothApisOpenTogetherExactlyAtMarketOpen() {
		givenReadySession();
		saveItem(samsung, MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveBriefingRow("간밤 기사가 이어졌습니다.");
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 0)));

		assertThat(briefingTitles(briefing())).containsExactly("전일 저녁 기사");
		assertThat(newsTitles(instrumentNewsQueryService.getInstrumentNews(samsung.getId())))
			.containsExactly("전일 저녁 기사");
	}

	@Test
	@DisplayName("items가 max-items-per-briefing으로 잘리고 공시 2건은 남으며 발행시각 내림차순이다")
	void truncatesToTheBriefingLimitKeepingDisclosuresAndSortingByPublishedAtDescending() {
		givenReadySession();
		int limit = properties.maxItemsPerBriefing();
		int newsCount = limit + FIXTURE_MARGIN;
		for (int index = 0; index < newsCount; index++) {
			Instrument owner = index % 2 == 0 ? samsung : hynix;
			saveItem(owner, MarketNewsItemType.NEWS, "전장 뉴스 " + index,
				LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 31)).plusMinutes(index));
		}
		saveItem(samsung, MarketNewsItemType.DISCLOSURE, "D-1 공시 A", PREVIOUS_TRADE_DATE.atStartOfDay());
		saveItem(hynix, MarketNewsItemType.DISCLOSURE, "D-1 공시 B", PREVIOUS_TRADE_DATE.atStartOfDay());
		saveBriefingRow("간밤 기사가 이어졌습니다.");

		MarketBriefingResponse response = briefing();

		assertThat(response.items()).hasSize(limit);
		assertThat(response.items())
			.filteredOn(item -> item.type() == MarketNewsItemType.DISCLOSURE)
			.as("브리핑은 전 종목 합산이라 규칙이 없으면 공시가 사실상 상시 전멸한다")
			.extracting(BriefingNewsItem::title)
			.containsExactly("D-1 공시 B", "D-1 공시 A");
		assertThat(response.items())
			.extracting(BriefingNewsItem::publishedAt)
			.isSortedAccordingTo(java.util.Comparator.reverseOrder());
		assertThat(briefingTitles(response))
			.contains("전장 뉴스 " + (newsCount - 1))
			.doesNotContain("전장 뉴스 0");
	}

	@Test
	@DisplayName("items가 여러 종목의 기사를 담고 항목마다 종목 정보를 갖는다")
	void carriesInstrumentIdentityForEveryItemAcrossInstruments() {
		givenReadySession();
		saveItem(samsung, MarketNewsItemType.NEWS, "삼성 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveItem(hynix, MarketNewsItemType.NEWS, "하이닉스 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(19, 0)));
		saveBriefingRow("간밤 기사가 이어졌습니다.");

		assertThat(briefing().items())
			.extracting(BriefingNewsItem::symbol, BriefingNewsItem::name, BriefingNewsItem::title)
			.containsExactly(
				org.assertj.core.groups.Tuple.tuple("000660", "SK하이닉스", "하이닉스 기사"),
				org.assertj.core.groups.Tuple.tuple("005930", "삼성전자", "삼성 기사"));
	}

	@Test
	@DisplayName("장중에 조회해도 브리핑 items에 장중 기사가 한 건도 없다")
	void neverIncludesIntradayArticlesInTheBriefing() {
		givenReadySession();
		saveItem(samsung, MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveItem(samsung, MarketNewsItemType.NEWS, "당일 새벽 기사",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(7, 0)));
		saveItem(samsung, MarketNewsItemType.NEWS, "장중 기사",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 30)));
		saveItem(hynix, MarketNewsItemType.NEWS, "오후 기사",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 0)));
		saveBriefingRow("간밤 기사가 이어졌습니다.");
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(14, 30)));

		assertThat(briefingTitles(briefing()))
			.containsExactly("당일 새벽 기사", "전일 저녁 기사");
	}

	@Test
	@DisplayName("장 마감 이후 조회에서도 브리핑 구간이 전장 그대로다")
	void keepsThePreMarketWindowEvenAfterTheClose() {
		givenReadySession();
		saveItem(samsung, MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveItem(samsung, MarketNewsItemType.NEWS, "장중 기사",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 0)));
		saveBriefingRow("간밤 기사가 이어졌습니다.");
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(18, 0)));

		assertThat(briefingTitles(briefing())).containsExactly("전일 저녁 기사");
	}

	@Test
	@DisplayName("D 접수 공시가 브리핑과 개장 직후 Part C 목록에 나오지 않는다")
	void neverExposesOriginDayDisclosuresInTheBriefingOrRightAfterOpen() {
		givenReadySession();
		saveItem(samsung, MarketNewsItemType.DISCLOSURE, "D-1 접수 공시", PREVIOUS_TRADE_DATE.atStartOfDay());
		saveItem(samsung, MarketNewsItemType.DISCLOSURE, "D 접수 공시", ORIGIN_TRADE_DATE.atStartOfDay());
		saveBriefingRow("간밤 기사가 이어졌습니다.");
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 0)));

		assertThat(briefingTitles(briefing())).doesNotContain("D 접수 공시");
		assertThat(newsTitles(instrumentNewsQueryService.getInstrumentNews(samsung.getId())))
			.doesNotContain("D 접수 공시");
	}

	@Test
	@DisplayName("D 접수 공시는 FULL 요약 입력에만 들어가고 전장 요약 입력에는 없다")
	void putsOriginDayDisclosuresOnlyIntoTheFullSummaryInput() {
		saveItem(samsung, MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveItem(samsung, MarketNewsItemType.DISCLOSURE, "D 접수 공시", ORIGIN_TRADE_DATE.atStartOfDay());

		assertThat(preMarketSummaryPromptTitles(samsung)).doesNotContain("D 접수 공시");
		org.mockito.Mockito.reset(narrativeService);
		when(narrativeService.resolveNewsSummaryNarrative(any()))
			.thenReturn(NarrativeResultDto.llm("하루 전체 기사가 이어졌습니다."));

		assertThat(fullSummaryPromptTitles(samsung)).contains("D 접수 공시");
	}

	@Test
	@DisplayName("D-1 접수 공시가 개장 시각에 브리핑·전장 요약·Part C items 셋 모두에 나온다")
	void showsPreviousDayDisclosureInBriefingSummaryAndItemsAtMarketOpen() {
		givenReadySession();
		saveItem(samsung, MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveItem(samsung, MarketNewsItemType.DISCLOSURE, "D-1 접수 공시", PREVIOUS_TRADE_DATE.atStartOfDay());
		saveBriefingRow("간밤 기사가 이어졌습니다.");
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(9, 0)));

		assertThat(briefingTitles(briefing()))
			.as("브리핑 items")
			.contains("D-1 접수 공시");
		assertThat(newsTitles(instrumentNewsQueryService.getInstrumentNews(samsung.getId())))
			.as("Part C items")
			.contains("D-1 접수 공시");
		assertThat(preMarketSummaryPromptTitles(samsung))
			.as("전장 요약 입력")
			.contains("D-1 접수 공시");
	}

	@Test
	@DisplayName("세션 미준비 조회는 EMPTY이고 originTradeDate가 null이다 — NOT_YET이 아니다")
	void returnsEmptyWithNullTradeDateWhenTheSessionIsNotReady() {
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(7, 0)));

		MarketBriefingResponse response = briefing();

		assertThat(response.status()).isEqualTo(FeedbackContentStatus.EMPTY);
		assertThat(response.originTradeDate()).isNull();
		assertThat(response.items()).isEmpty();
	}

	@Test
	@DisplayName("같은 세션 미준비 시각에 Part C는 NOT_YET이고 Part D는 EMPTY다")
	void differsFromPartCWhenTheSessionIsNotReady() {
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(7, 0)));

		assertThat(briefing().status()).isEqualTo(FeedbackContentStatus.EMPTY);
		assertThat(instrumentNewsQueryService.getInstrumentNews(samsung.getId()).summaryStatus())
			.isEqualTo(FeedbackContentStatus.NOT_YET);
	}

	@Test
	@DisplayName("세션은 READY이고 개장 전이면 NOT_YET이고 originTradeDate가 채워진다")
	void returnsNotYetWithTheTradeDateBeforeOpen() {
		givenReadySession();
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 41)));

		MarketBriefingResponse response = briefing();

		assertThat(response.status()).isEqualTo(FeedbackContentStatus.NOT_YET);
		assertThat(response.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
	}

	@Test
	@DisplayName("전장 구간 기사가 0건이면 EMPTY이고 items도 빈다")
	void returnsEmptyWithNoItemsWhenThePreMarketWindowIsEmpty() {
		givenReadySession();
		saveItem(samsung, MarketNewsItemType.NEWS, "장중 기사",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 0)));
		saveBriefingRow("간밤 기사가 이어졌습니다.");

		MarketBriefingResponse response = briefing();

		assertThat(response.status()).isEqualTo(FeedbackContentStatus.EMPTY);
		assertThat(response.items()).isEmpty();
		assertThat(response.summary()).isNull();
	}

	@Test
	@DisplayName("브리핑 행이 없고 기사가 있으면 EMPTY이고 items는 채워진다")
	void returnsEmptyWithFilledItemsWhenTheBriefingRowIsMissing() {
		givenReadySession();
		saveItem(samsung, MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));

		MarketBriefingResponse response = briefing();

		assertThat(response.status()).isEqualTo(FeedbackContentStatus.EMPTY);
		assertThat(briefingTitles(response)).containsExactly("전일 저녁 기사");
		assertThat(response.summary()).isNull();
	}

	@Test
	@DisplayName("행이 있고 summary가 NULL이면 UNAVAILABLE이고 items는 채워진다")
	void returnsUnavailableWithFilledItemsWhenTheStoredSummaryIsNull() {
		givenReadySession();
		saveItem(samsung, MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveBriefingRow(null);

		MarketBriefingResponse response = briefing();

		assertThat(response.status()).isEqualTo(FeedbackContentStatus.UNAVAILABLE);
		assertThat(response.summary()).isNull();
		assertThat(briefingTitles(response)).containsExactly("전일 저녁 기사");
	}

	@Test
	@DisplayName("행이 있고 서술이 있으면 READY이고 문장이 실린다")
	void returnsReadyWithTheStoredNarrative() {
		givenReadySession();
		saveItem(samsung, MarketNewsItemType.NEWS, "전일 저녁 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveBriefingRow("간밤 기사가 이어졌습니다.");

		MarketBriefingResponse response = briefing();

		assertThat(response.status()).isEqualTo(FeedbackContentStatus.READY);
		assertThat(response.summary()).isEqualTo("간밤 기사가 이어졌습니다.");
		assertThat(response.market()).isEqualTo(Market.STOCK);
		assertThat(response.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
	}

}
