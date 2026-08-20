// 고정 Clock + 실 MySQL로 종목 뉴스 조회의 노출 게이트·범위·상한·상태값을 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.FeedbackContentStatus;
import com.finplay.api.feedback.domain.InstrumentNewsSummary;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.feedback.dto.response.NewsItem;
import com.finplay.api.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.store.FeedbackQueryCacheTestKeys;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.market.service.InstrumentService;
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

// 완료 조건 노출 게이트 ⑧의 Part C 절반·⑨·⑩과 상태값 ②③④가 이 파일의 목표다. 인증·직렬화는
// InstrumentNewsControllerTest가, §C-4 판정 순서는 InstrumentNewsQueryServiceTest가 맡는다.
//
// 게이트를 별도 필터가 아니라 구간 질의로 구현했으므로(1배속 재생이라 원본 거래일 시각과 서비스 날짜의
// 벽시계가 1:1로 대응한다) 그 등가가 실제로 성립하는지를 여기서 확인한다 — mock으로는 "어떤 인자로
// 물었는가"까지만 보이고, 저장된 published_at 위에서 실제로 걸러지는지는 실 DB에서만 드러난다.
//
// 공유 컨테이너를 더럽히지 않도록 클래스 트랜잭션으로 감싼다 (PriceMoveQueryGateIntegrationTest 선례).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	TestClockConfig.class})
class InstrumentNewsQueryGateIntegrationTest {

	// 원본 거래일 D = 2026-08-05(수), 직전 영업일 D-1 = 2026-08-04(화), 서비스 날짜 = 2026-08-06(목)
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final LocalDateTime INITIAL_NOW = LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 0));

	private static final String STOCK_SYMBOL = "005930";

	// 상한 위로 얼마나 더 심을지. 공시 2건이 전부 살아남고도 뉴스가 잘리려면 3건이면 충분하다.
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

	// 상한을 상수로 옮겨 적지 않고 빈에서 읽는다. 옮겨 적으면 §튜닝으로 값을 올렸을 때 픽스처가 상한 아래로
	// 내려가 절단이 조용히 사라지는데, 그때도 테스트는 전부 초록이다 — spec §뉴스 매칭 범위가 경고한 형태다.
	// 드리프트 테스트 2축은 값 자체는 잡지만 "픽스처가 상한 위"라는 성질은 잡지 못한다.
	@Autowired
	private FeedbackNewsProperties properties;

	@Autowired
	private TestClock clock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private TestClock mutableClock;

	private Instrument instrument;

	// 조회 캐시(#245)가 켜진 뒤 필요해진 격리 훅이다. 이 클래스는 @Transactional이라 DB는 롤백되지만 공유
	// Testcontainers Redis는 롤백되지 않는다 — 요약 캐시 키가 (종목, 원본 거래일, scope)라 여러 메서드가 같은
	// 키를 쓰므로, saveSummary로 채운 메서드가 먼저 돌면 EMPTY·UNAVAILABLE 단정이 READY를 보게 된다.
	// 지금까지 통과한 것은 JUnit5 기본 메서드 순서가 우연히 유리했기 때문이고, 메서드 이름 하나만 바뀌어도
	// 뒤집힌다(같은 원인으로 MarketBriefingQueryGateIntegrationTest는 실제로 8건이 깨졌다 —
	// ai/agent-mistakes.md). @AfterEach가 아니라 @BeforeEach인 이유는 앞 테스트가 정리에 실패해도 이번
	// 테스트가 항상 빈 캐시에서 시작하게 하기 위해서다.
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

	// --- 게이트 ⑧(Part C 절반) 상한과 정렬 ---

	// 픽스처 개수를 실제 상한에서 계산한다 — 뉴스 (상한 + 3)건 + D-1 접수 공시 2건이라 §튜닝으로 상한을
	// 어떻게 조정해도 항상 상한 위다. 공시는 published_at이 00:00:00이라 내림차순 목록의 최하위이고,
	// "공시 우선"이 없으면 상한을 넘는 순간 둘 다 먼저 잘린다.
	@Test
	@DisplayName("items가 max-items-per-news-list로 잘리고 공시 2건은 남으며 발행시각 내림차순이다")
	void truncatesToTheConfiguredLimitKeepingDisclosuresAndSortingByPublishedAtDescending() {
		givenReadySession();
		int limit = properties.maxItemsPerNewsList();
		int newsCount = limit + FIXTURE_MARGIN;
		// 1분 간격이라 마지막 기사도 전장 구간 [D-1 15:30, D 09:00] 안에 있다.
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
		// 남은 자리는 가장 늦게 발행된 뉴스다 — 가장 이른 "전장 뉴스 0"은 잘려 나간다.
		assertThat(titles(response))
			.contains("전장 뉴스 " + (newsCount - 1))
			.doesNotContain("전장 뉴스 0");
	}

	// --- 게이트 ⑨ 범위 — 다른 원본 거래일 기사가 섞이지 않는다 ---

	@Test
	@DisplayName("전장 하한 이전과 원본 거래일 이후의 기사가 목록에 섞이지 않는다")
	void neverMixesArticlesFromOtherOriginTradeDates() {
		givenReadySession();
		saveItem(MarketNewsItemType.NEWS, "구간 안 기사",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		// 하한(D-1 15:30) 1분 전 — 그 앞은 D-1의 장중이라 이전 재생일의 기사군이다.
		saveItem(MarketNewsItemType.NEWS, "하한 1분 전",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 29)));
		// 다음 거래일 새벽 — 상한을 열어 두면 이 기사가 그날 목록에 섞인다.
		saveItem(MarketNewsItemType.NEWS, "다음 거래일 새벽",
			LocalDateTime.of(ORIGIN_TRADE_DATE.plusDays(1), LocalTime.of(3, 0)));

		assertThat(titles(query())).containsExactly("구간 안 기사");
	}

	// 하한 정각은 포함이다 — 요약 생성 구간과 하한이 같아야 게이트 ⑦(Part C·D 하한 일치)이 성립한다.
	@Test
	@DisplayName("전장 하한 15:30 정각 기사는 목록에 들어온다")
	void includesTheArticleExactlyAtTheLowerBound() {
		givenReadySession();
		saveItem(MarketNewsItemType.NEWS, "하한 정각",
			LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 30)));

		assertThat(titles(query())).containsExactly("하한 정각");
	}

	// 장 마감 후 조회에서도 상한이 15:30에서 멈춘다 — 열어 두면 다음 거래일 새벽 기사가 섞인다.
	@Test
	@DisplayName("장 마감 이후 조회에서도 원본 거래일 15:30 이후 기사는 나오지 않는다")
	void keepsTheUpperBoundAtTheCloseWhenQueriedAfterTradingHours() {
		givenReadySession();
		saveItem(MarketNewsItemType.NEWS, "장중 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 0)));
		saveItem(MarketNewsItemType.NEWS, "마감 후 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(16, 0)));
		mutableClock.set(LocalDateTime.of(SERVICE_DATE, LocalTime.of(18, 0)));

		assertThat(titles(query())).containsExactly("장중 기사");
	}

	// --- 게이트 등가 — 구간 질의가 곧 "재생 시각을 지난 기사만 노출"이다 ---

	// 별도 필터 없이 상한을 현재 시각으로 두는 구현이라, 그 등가가 실제 데이터 위에서 성립하지 않으면
	// 게이트 ⑨가 깨진다. 같은 픽스처를 두 시각에서 조회해 열림·닫힘 양쪽을 본다.
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

	// 전장 기사는 클램프 결과가 09:00이고 이 경로에는 09:00 이후에만 들어오므로 개장 직후 전부 열려 있어야
	// 한다 — 구간 질의로 바꾸면서 이 성질이 유지되는지가 등가의 나머지 절반이다.
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

	// --- 게이트 ⑩ 장중에는 전장 요약, 장 마감 이후에는 FULL ---

	// 두 범위의 행을 모두 심어 두는 것이 요지다. 장중 조회가 FULL 행을 집으면 요약 한 문장이 그날 오후를
	// 통째로 알려준다 — items에 게이트를 걸어도 막을 수 없는 유출이다 (FEED-008).
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

	// --- 상태값 ②③④ 종단 확인 ---

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

	// 저장된 행만으로는 EMPTY와 구분되지 않는다 — 둘 다 summary가 NULL이다(§C-8). 행 존재로만 갈린다.
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
