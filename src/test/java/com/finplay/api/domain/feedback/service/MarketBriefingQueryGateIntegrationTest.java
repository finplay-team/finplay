// 고정 Clock + 실 MySQL로 개장 전 브리핑 조회의 게이트·범위·상한·상태값과 Part C와의 대칭을 검증한다.
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

// 완료 조건 게이트 ⑦·⑧의 Part D 절반·⑪·⑫와 상태값 ①이 이 파일의 목표다. 인증·직렬화·market 400은
// MarketBriefingControllerTest가, §C-4 판정 순서와 응답 상한은 MarketBriefingServiceTest가 맡는다.
//
// 게이트 ⑦은 "개장 전 한 시각에 두 API를 모두 호출한다"가 완료 조건 문장이라 두 서비스를 함께 주입한다.
// 두 경로가 MarketSessionTimes의 같은 상수를 보는 것은 회귀 방지이고, 조건 자체는 종단 호출로 닫는다.
//
// NarrativeService를 mock으로 둔 것은 게이트 ⑫ 때문이다 — "D-1 접수 공시가 전장 요약에 나온다"는 저장된
// 행으로는 볼 수 없다(요약은 문장만 저장하고 입력 목록을 남기지 않는다). 생성 프롬프트를 잡아 확인한다.
//
// 공유 컨테이너를 더럽히지 않도록 클래스 트랜잭션으로 감싼다 (PriceMoveQueryGateIntegrationTest 선례).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	TestClockConfig.class})
class MarketBriefingQueryGateIntegrationTest {

	// 원본 거래일 D = 2026-08-05(수), 직전 영업일 D-1 = 2026-08-04(화), 서비스 날짜 = 2026-08-06(목)
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final LocalDateTime INITIAL_NOW = LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 0));

	// 상한 위로 얼마나 더 심을지. 공시 2건이 전부 살아남고도 뉴스가 잘리려면 3건이면 충분하다.
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

	// 상한을 상수로 옮겨 적지 않고 빈에서 읽는다. 옮겨 적으면 §튜닝으로 값을 올렸을 때 픽스처가 상한 아래로
	// 내려가 절단이 조용히 사라지는데, 그때도 테스트는 전부 초록이다 — spec §뉴스 매칭 범위가 경고한 형태다.
	@Autowired
	private FeedbackNewsProperties properties;

	@MockitoBean
	private NarrativeService narrativeService;

	@Autowired
	private TestClock clock;

	private TestClock mutableClock;

	private Instrument samsung;

	private Instrument hynix;

	// 조회 캐시(#245)가 켜진 뒤 필요해진 격리 훅이다. 이 클래스는 @Transactional이라 DB는 롤백되지만 공유
	// Testcontainers Redis는 롤백되지 않아, 앞 메서드가 캐시한 브리핑 텍스트·items가 뒤 메서드에 그대로 보인다
	// (실제로 8건이 그렇게 깨졌다 — ai/agent-mistakes.md). @AfterEach가 아니라 @BeforeEach인 것은 앞
	// 테스트가 정리에 실패해도 이번 테스트가 항상 빈 캐시에서 시작하게 하기 위해서다.
	//
	// 캐시를 끄지 않는다 — 게이트 단정들이 운영과 같은 배선(캐시 켜짐)을 그대로 지나가야 계약 불변의 증거가 된다.
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

	// 요약·브리핑 생성 입력은 저장되지 않으므로 프롬프트를 잡아야 안을 볼 수 있다.
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

	// --- 게이트 ⑦ 개장 전 한 시각에 두 API를 모두 호출한다 ---

	// 완료 조건 문장 그대로다. 한쪽에만 09:00 하한이 있으면 08:41에 그쪽으로 조회해 다른 쪽이 감추는
	// 전장 기사를 먼저 볼 수 있다 — 두 API가 같은 기사군을 다루므로 하한이 어긋나면 그대로 유출이 된다.
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
		// 두 API 모두 같은 원본 거래일을 채운다 — 하한만이 아니라 기준 거래일도 같아야 대칭이 성립한다.
		assertThat(partD.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
		assertThat(partC.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
	}

	// 하한이 같다는 것의 반대편 — 09:00 정각에는 두 API가 함께 열린다. 한쪽만 열리면 그 순간에도 비대칭이다.
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

	// --- 게이트 ⑧ (Part D 절반) 상한과 정렬 ---

	// 픽스처 개수를 실제 상한에서 계산한다 — 뉴스 (상한 + 3)건 + D-1 접수 공시 2건이라 §튜닝으로 상한을
	// 어떻게 조정해도 항상 상한 위다. 전 종목 합산이라 종목당 2건만 쌓여도 넘는 자리이며, 공시는
	// published_at이 00:00:00이라 내림차순 목록의 최하위다 — "공시 우선"이 없으면 둘 다 먼저 잘린다.
	@Test
	@DisplayName("items가 max-items-per-briefing으로 잘리고 공시 2건은 남으며 발행시각 내림차순이다")
	void truncatesToTheBriefingLimitKeepingDisclosuresAndSortingByPublishedAtDescending() {
		givenReadySession();
		int limit = properties.maxItemsPerBriefing();
		int newsCount = limit + FIXTURE_MARGIN;
		// 1분 간격이라 마지막 기사도 전장 구간 [D-1 15:30, D 09:00] 안에 있다.
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

	// 시장 단일 목록이라 종목이 섞인다 — 항목마다 종목을 붙이지 않으면 어느 종목 소식인지 알 수 없다.
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

	// --- 게이트 ⑪ 브리핑에 장중 기사가 한 건도 없다 ---

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

	// 장 마감 이후에도 전장 그대로다 — Part C는 이 시각에 FULL로 넓어지지만 브리핑은 넓어지지 않는다.
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

	// --- 게이트 ⑫ 공시 날짜 판정 ---

	// D 접수 공시는 FULL에서만 나온다(§C-3). 브리핑과 개장 직후 Part C에 새면 그날 장중 접수분이 미리 노출된다.
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

	// 완료 조건의 나머지 절반 — D-1 접수 공시는 개장 시각에 브리핑·전장 요약·Part C items 셋 모두에 나온다.
	// 요약 입력은 저장되지 않으므로 프롬프트를 잡아 본다.
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

	// --- 상태값 ① 6단계 판정 ---

	// 이 한 건이 Part C와 갈리는 자리다. Part C는 같은 상황에서 NOT_YET이고 Part D는 EMPTY다 — Part D는
	// "브리핑이 아예 없는 날"이 정상이기 때문이며, originTradeDate까지 null인 것이 표식이다.
	@Test
	@DisplayName("세션 미준비 조회는 EMPTY이고 originTradeDate가 null이다 — NOT_YET이 아니다")
	void returnsEmptyWithNullTradeDateWhenTheSessionIsNotReady() {
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(7, 0)));

		MarketBriefingResponse response = briefing();

		assertThat(response.status()).isEqualTo(FeedbackContentStatus.EMPTY);
		assertThat(response.originTradeDate()).isNull();
		assertThat(response.items()).isEmpty();
	}

	// 같은 시각에 Part C는 NOT_YET이다 — 두 API의 의도된 차이라 한 자리에서 나란히 본다.
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

	// 저장된 행만으로는 EMPTY와 구분되지 않는다 — 둘 다 summary가 NULL이다(§C-8). 행 존재로만 갈린다.
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
