// 고정 Clock + Testcontainers로 수집 종단을 검증한다 — 기사 당일 저장, 발행일자 무필터, 중복 무시, 원장 불변.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.collector.CollectedNewsDto;
import com.finplay.api.domain.feedback.collector.DisclosureCollector;
import com.finplay.api.domain.feedback.collector.NewsCollector;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.feed.BithumbFeedSimulator;
import com.finplay.api.domain.market.feed.BithumbFeedStatusReconciler;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

// tasks.md 5번의 완료 조건 ①③④가 이 파일의 목표다. ②(`전장` 구간)는 저장으로는 드러나지 않아
// NewsCollectionScheduleTest가 CronExpression으로 따로 단정한다.
//
// 수집기는 @MockitoBean으로 바꿔 D의 기사를 주도록 고정한다 — FakeNewsCollector는 빈 목록이 계약이라
// 저장 경로를 태울 수 없고, 실제 외부 호출은 금지다(PRD C-005).
//
// 공유 컨테이너를 더럽히지 않도록 클래스 트랜잭션으로 감싼다 (AccountSummaryIntegrationTest 선례).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class NewsCollectionIntegrationTest {

	// D = 2026-08-05(수). 수집은 이 시각에 돈다.
	private static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 8, 5, 10, 30);

	// D 아침 발행 — §C-2 `전장` 구간에 드는 기사
	private static final LocalDateTime PUBLISHED_ON_D = LocalDateTime.of(2026, 8, 5, 8, 30);

	// 어느 구간에도 걸리지 않는 발행 시각
	private static final LocalDateTime PUBLISHED_LONG_AGO = LocalDateTime.of(2026, 5, 11, 11, 0);

	private static final String STOCK_SYMBOL = "005930";
	private static final String URL_PREFIX = "https://news.example.test/collect/";

	// 수집 실행 전후로 행이 변하면 안 되는 원장 테이블 (주문·체결·계좌/잔액·보유·손익 배분)
	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	@MockitoBean
	private NewsCollector newsCollector;

	@MockitoBean
	private DisclosureCollector disclosureCollector;

	@Autowired
	private NewsCollectionService newsCollectionService;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ApplicationContext applicationContext;

	@Autowired
	private Environment environment;

	@Autowired
	private TestClock clock;

	private TestClock mutableClock;

	@BeforeEach
	void setUp() {
		mutableClock = clock;
		mutableClock.set(COLLECTED_AT);
	}

	// ① 기사 당일 수집 — D에 수집하면 D 시점에 저장되고, D+1에는 다시 긁지 않고 조회만 해도 그 기사가 있다.
	@Test
	@DisplayName("D의 기사가 D 시점에 저장되고 D+1에는 조회만으로 그대로 있다")
	void savesArticleOnTheDayItIsPublishedAndKeepsItForTheNextDay() {
		givenNewsForStock(news("D 아침 기사", "day", PUBLISHED_ON_D));

		newsCollectionService.collectNews();

		MarketNewsItem saved = findSaved("day");
		assertThat(saved.getType()).isEqualTo(MarketNewsItemType.NEWS);
		assertThat(saved.getPublishedAt()).isEqualTo(PUBLISHED_ON_D);
		// created_at은 발행 시각이 아니라 수집 시각이고, 그 값이 D다 (§데이터 모델).
		assertThat(saved.getCreatedAt()).isEqualTo(COLLECTED_AT);

		// D+1로 시계를 옮기고 수집을 다시 돌리지 않는다 — 재생 시점에 다시 긁는 경로가 없어야 한다.
		mutableClock.set(COLLECTED_AT.plusDays(1));

		MarketNewsItem nextDay = findSaved("day");
		assertThat(nextDay.getId()).isEqualTo(saved.getId());
		assertThat(nextDay.getCreatedAt()).isEqualTo(COLLECTED_AT);
	}

	// ③ 발행일자로 거르지 않는다 — 어느 구간에도 걸리지 않는 기사도 그대로 저장된다 (FEED-001).
	@Test
	@DisplayName("어느 구간에도 걸리지 않는 발행 시각의 기사도 그대로 저장된다")
	void storesArticlesWithoutFilteringByPublishedDate() {
		givenNewsForStock(
			news("석 달 전 기사", "old", PUBLISHED_LONG_AGO),
			news("D 아침 기사", "day", PUBLISHED_ON_D));

		newsCollectionService.collectNews();

		assertThat(findSaved("old").getPublishedAt()).isEqualTo(PUBLISHED_LONG_AGO);
		assertThat(findSaved("day").getPublishedAt()).isEqualTo(PUBLISHED_ON_D);
	}

	// FEED-001 — 중복은 오류가 아니라 무시한다. 같은 기사가 30분마다 다시 조회되는 것이 정상 동작이다.
	@Test
	@DisplayName("같은 (종목, url)이 두 번 수집돼도 한 건만 남는다")
	void ignoresDuplicateArticleOnSecondCollection() {
		givenNewsForStock(news("같은 기사", "dup", PUBLISHED_ON_D));

		newsCollectionService.collectNews();
		mutableClock.set(COLLECTED_AT.plusMinutes(30));
		newsCollectionService.collectNews();

		assertThat(savedItems("dup")).hasSize(1);
		// 두 번째 실행의 수집 시각으로 덮어쓰지도 않는다.
		assertThat(findSaved("dup").getCreatedAt()).isEqualTo(COLLECTED_AT);
	}

	// ④ 원장 불변 — 수집 실행 전후로 주문·체결·계좌·잔액·보유·손익 테이블의 행이 변하지 않는다.
	@Test
	@DisplayName("수집 실행 전후로 원장 테이블의 행이 변하지 않는다")
	void neverTouchesLedgerTables() {
		givenNewsForStock(news("원장 검증용 기사", "ledger", PUBLISHED_ON_D));
		Map<String, Long> before = ledgerRowCounts();

		newsCollectionService.collectNews();
		newsCollectionService.collectDisclosures();

		// 수집이 실제로 쓰기를 했는데도 원장이 그대로여야 의미가 있다.
		assertThat(savedItems("ledger")).hasSize(1);
		assertThat(ledgerRowCounts()).isEqualTo(before);
	}

	// §C-1 — 풀이 부족하면 개장 시간대에 SSE heartbeat와 매분 가격 push가 밀린다(이슈 #125).
	@Test
	@DisplayName("spring.task.scheduling.pool.size가 등록된 @Scheduled 수 이상이다")
	void schedulingPoolIsLargeEnoughForEveryScheduledTask() {
		int poolSize = Integer.parseInt(
			environment.getRequiredProperty("spring.task.scheduling.pool.size"));
		int registered = countScheduledMethods();
		// 스캔이 0을 세면 이 단정은 공허하게 통과한다 — 최소한 기존 4개 + 신설 2개는 잡혀야 한다.
		assertThat(registered).as("컨텍스트에서 @Scheduled를 하나도 세지 못했다").isGreaterThanOrEqualTo(6);
		// BithumbFeedSimulator·BithumbFeedStatusReconciler는 기본 프로필에서 도는 스케줄이지만 테스트에서만
		// 프로퍼티로 꺼져 있다(build.gradle의 spring.config.additional-location). 실제 기본 프로필 개수로
		// 환산해 비교한다 — 보정하지 않으면 풀이 실제 프로덕션 스케줄 수보다 작아도 통과할 수 있다.
		int disabledInTestsOnly = countIfDisabledInTests(BithumbFeedSimulator.class)
			+ countIfDisabledInTests(BithumbFeedStatusReconciler.class);

		assertThat(poolSize)
			.as("등록된 @Scheduled %d개(+테스트에서만 꺼진 %d개)보다 풀이 작다",
				registered, disabledInTestsOnly)
			.isGreaterThanOrEqualTo(registered + disabledInTestsOnly);
	}

	@Test
	@DisplayName("수집 스케줄 2종이 실제 컨텍스트에 등록되어 있다")
	void bothCollectionSchedulesAreRegisteredInContext() {
		assertThat(scheduledMethodNames(NewsCollectionService.class))
			.contains("collectNews", "collectDisclosures");
	}

	// 빈이 아예 없으면 그 클래스의 @Scheduled 1개가 테스트에서만 빠진 것으로 본다.
	private int countIfDisabledInTests(Class<?> scheduledBeanType) {
		return applicationContext.getBeanNamesForType(scheduledBeanType).length == 0 ? 1 : 0;
	}

	private void givenNewsForStock(CollectedNewsDto... articles) {
		when(newsCollector.collect(argThat(this::isTargetStock), any())).thenReturn(List.of(articles));
	}

	private boolean isTargetStock(Instrument instrument) {
		return instrument != null && STOCK_SYMBOL.equals(instrument.getSymbol());
	}

	private static CollectedNewsDto news(String title, String urlKey, LocalDateTime publishedAt) {
		return new CollectedNewsDto(title, "hankyung.com", URL_PREFIX + urlKey, publishedAt);
	}

	private MarketNewsItem findSaved(String urlKey) {
		List<MarketNewsItem> items = savedItems(urlKey);
		assertThat(items).as("%s 기사가 저장되지 않았다", urlKey).hasSize(1);
		return items.get(0);
	}

	private List<MarketNewsItem> savedItems(String urlKey) {
		Long instrumentId = targetInstrumentId();
		return marketNewsItemRepository.findAll().stream()
			.filter(item -> item.getUrl().equals(URL_PREFIX + urlKey))
			.filter(item -> item.getInstrument().getId().equals(instrumentId))
			.toList();
	}

	private Long targetInstrumentId() {
		return instrumentService.getInstrumentEntities(Market.STOCK).stream()
			.filter(this::isTargetStock)
			.findFirst()
			.orElseThrow()
			.getId();
	}

	private Map<String, Long> ledgerRowCounts() {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : LEDGER_TABLES) {
			counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		return counts;
	}

	// 컨텍스트에 등록된 빈들의 @Scheduled 메서드 수를 센다. 빈을 새로 만들지 않도록 타입만 조회한다.
	private int countScheduledMethods() {
		int count = 0;
		for (String beanName : applicationContext.getBeanDefinitionNames()) {
			Class<?> type = beanTypeOrNull(beanName);
			if (type != null) {
				count += scheduledMethodNames(type).size();
			}
		}
		return count;
	}

	private Class<?> beanTypeOrNull(String beanName) {
		try {
			Class<?> type = applicationContext.getType(beanName);
			return type == null ? null : ClassUtils.getUserClass(type);
		} catch (RuntimeException ex) {
			return null;
		}
	}

	private static List<String> scheduledMethodNames(Class<?> type) {
		return java.util.Arrays.stream(ReflectionUtils.getAllDeclaredMethods(type))
			.filter(method -> method.isAnnotationPresent(Scheduled.class))
			.map(java.lang.reflect.Method::getName)
			.distinct()
			.toList();
	}

}
