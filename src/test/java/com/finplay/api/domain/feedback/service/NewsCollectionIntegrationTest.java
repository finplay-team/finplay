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

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class NewsCollectionIntegrationTest {

	private static final LocalDateTime COLLECTED_AT = LocalDateTime.of(2026, 8, 5, 10, 30);

	private static final LocalDateTime PUBLISHED_ON_D = LocalDateTime.of(2026, 8, 5, 8, 30);

	private static final LocalDateTime PUBLISHED_LONG_AGO = LocalDateTime.of(2026, 5, 11, 11, 0);

	private static final String STOCK_SYMBOL = "005930";
	private static final String URL_PREFIX = "https://news.example.test/collect/";

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

	@Test
	@DisplayName("D의 기사가 D 시점에 저장되고 D+1에는 조회만으로 그대로 있다")
	void savesArticleOnTheDayItIsPublishedAndKeepsItForTheNextDay() {
		givenNewsForStock(news("D 아침 기사", "day", PUBLISHED_ON_D));

		newsCollectionService.collectNews();

		MarketNewsItem saved = findSaved("day");
		assertThat(saved.getType()).isEqualTo(MarketNewsItemType.NEWS);
		assertThat(saved.getPublishedAt()).isEqualTo(PUBLISHED_ON_D);
		assertThat(saved.getCreatedAt()).isEqualTo(COLLECTED_AT);

		mutableClock.set(COLLECTED_AT.plusDays(1));

		MarketNewsItem nextDay = findSaved("day");
		assertThat(nextDay.getId()).isEqualTo(saved.getId());
		assertThat(nextDay.getCreatedAt()).isEqualTo(COLLECTED_AT);
	}

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

	@Test
	@DisplayName("같은 (종목, url)이 두 번 수집돼도 한 건만 남는다")
	void ignoresDuplicateArticleOnSecondCollection() {
		givenNewsForStock(news("같은 기사", "dup", PUBLISHED_ON_D));

		newsCollectionService.collectNews();
		mutableClock.set(COLLECTED_AT.plusMinutes(30));
		newsCollectionService.collectNews();

		assertThat(savedItems("dup")).hasSize(1);
		assertThat(findSaved("dup").getCreatedAt()).isEqualTo(COLLECTED_AT);
	}

	@Test
	@DisplayName("수집 실행 전후로 원장 테이블의 행이 변하지 않는다")
	void neverTouchesLedgerTables() {
		givenNewsForStock(news("원장 검증용 기사", "ledger", PUBLISHED_ON_D));
		Map<String, Long> before = ledgerRowCounts();

		newsCollectionService.collectNews();
		newsCollectionService.collectDisclosures();

		assertThat(savedItems("ledger")).hasSize(1);
		assertThat(ledgerRowCounts()).isEqualTo(before);
	}

	@Test
	@DisplayName("spring.task.scheduling.pool.size가 등록된 @Scheduled 수 이상이다")
	void schedulingPoolIsLargeEnoughForEveryScheduledTask() {
		int poolSize = Integer.parseInt(
			environment.getRequiredProperty("spring.task.scheduling.pool.size"));
		int registered = countScheduledMethods();
		assertThat(registered).as("컨텍스트에서 @Scheduled를 하나도 세지 못했다").isGreaterThanOrEqualTo(6);
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
