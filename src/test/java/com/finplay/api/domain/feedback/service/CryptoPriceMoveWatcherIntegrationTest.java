// 고정 Clock + Testcontainers(MySQL·Redis)로 코인 변동 감시(CryptoPriceMoveWatcher)의 종단을 검증한다 — 카드 생성과 원장 불변.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.feedback.collector.CollectedNewsDto;
import com.finplay.api.domain.feedback.collector.NewsCollector;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.PriceStore;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

// tasks.md 3번 항목의 완료 조건("5분 전 가격을 실제로 꺼낸다", 근거 매칭·서술·저장까지 실 협력자로 종단 확인)과
// 8개 이슈 공통 조건(원장 불변, 이 배치가 맡는 몫)이 이 파일의 목표다. σ 표본의 정확한 수치·자정 케이스·
// 쿨다운·일일 상한의 경계는 CryptoPriceMoveWatcherTest(단위)가 mock으로 이미 정밀하게 본다 — 여기서는 Redis
// 스냅샷 → σ 계산 → MySQL 근거 매칭 → 카드 저장까지 실제 컨테이너로 한 번 이어지는지만 본다(ADR-0003).
//
// LLM은 부르지 않는다 — application.yml의 api-key가 "not-configured"라 NarrativeService가 §템플릿 문장으로
// 폴백한다(FeedbackBatchIntegrationTest와 같은 전제).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class CryptoPriceMoveWatcherIntegrationTest {

	// 배치 실행 시각 — 자정과 무관한 평범한 시각이다. 자정 케이스는 단위 테스트가 정밀하게 본다.
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0);

	private static final String SYMBOL = "MOVEWATCH";

	// 종목별 실패 격리 테스트 전용 두 번째 종목.
	private static final String SYMBOL2 = "MOVEWATCH2";

	// 배치 실행 전후로 행이 변하면 안 되는 원장 테이블 (다른 원장 불변 테스트와 같은 목록).
	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	// 이 배치가 읽기만 해야 하는 테이블. market_news_items는 첫 매칭이 성공해 온디맨드 수집(ADR-0017)이
	// 트리거되지 않는 시나리오에서만 읽기 전용이다 — 이 상수를 쓰는 테스트가 사전에 givenMatchingNews()로
	// 근거를 채워 온디맨드 경로를 타지 않게 하는지 확인하고 재사용해야 한다.
	private static final List<String> READ_ONLY_TABLES = List.of("instruments", "market_news_items");

	// tasks-285.md 4번 항목 — 온디맨드 수집(ADR-0017)을 실 협력자로 종단 검증하려고 NewsCollector만 mock으로
	// 갈아끼운다. FakeNewsCollector는 빈 목록이 계약이라 저장 경로를 태울 수 없다(NewsCollectionIntegrationTest
	// 선례와 같은 이유).
	@MockitoBean
	private NewsCollector newsCollector;

	@Autowired
	private CryptoPriceMoveWatcher cryptoPriceMoveWatcher;

	@Autowired
	private NewsCollectionService newsCollectionService;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	@Autowired
	private PriceStore priceStore;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	// 전역 Clock 빈을 대신하는 공용 테스트 시계 (TestClockConfig). 기준 시각은 @BeforeEach에서 세운다.
	@Autowired
	private TestClock clock;

	private Instrument instrument;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		instrument = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, SYMBOL, "테스트코인", BigDecimal.ONE, 5000L, true, NOW));
	}

	@AfterEach
	void cleanUpRedis() {
		redisTemplate.delete("price:crypto:" + SYMBOL + ":snapshots");
		redisTemplate.delete("price:crypto:" + SYMBOL2 + ":snapshots");
	}

	// min-sample-count(기본 100) 세그먼트를 실제로 채우는 5분 간격 스냅샷 101개 — ago 0~5분은 점프 이후,
	// 그 뒤(ago 5~500분)는 점프 이전이다. "5분 전 가격을 실제로 꺼내 쓴다"(완료 조건 1)가 이 픽스처의 핵심이다.
	private void givenEnoughSnapshotsWithARecentJump() {
		givenEnoughSnapshotsWithARecentJump(SYMBOL);
	}

	// 종목별 실패 격리 테스트가 두 번째 종목에도 같은 점프 픽스처를 채우려고 심볼을 매개변수로 뺀 버전.
	private void givenEnoughSnapshotsWithARecentJump(String symbol) {
		BigDecimal past = BigDecimal.valueOf(100);
		BigDecimal now = BigDecimal.valueOf(100 * Math.exp(0.12));
		Duration retention = Duration.ofHours(24);
		for (int agoMinutes = 500; agoMinutes >= 5; agoMinutes -= 5) {
			priceStore.recordSnapshot(symbol, NOW.minusMinutes(agoMinutes), past, retention);
		}
		priceStore.recordSnapshot(symbol, NOW, now, retention);
	}

	private MarketNewsItem givenMatchingNews() {
		return givenMatchingNews(instrument, "https://news.example.com/move-watch");
	}

	private MarketNewsItem givenMatchingNews(Instrument targetInstrument, String url) {
		return marketNewsItemRepository.save(MarketNewsItem.create(
			targetInstrument, MarketNewsItemType.NEWS, "테스트 급등 기사", "테스트경제",
			url, NOW.minusMinutes(5), NOW));
	}

	private Instrument givenSecondCryptoInstrument() {
		return instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, SYMBOL2, "테스트코인2", BigDecimal.ONE, 5000L, true, NOW));
	}

	// 온디맨드 수집이 돌려줄 근거 기사 — publishedAt은 crypto.match-before-minutes(35) 안쪽인 5분 전으로 둔다.
	private static CollectedNewsDto onDemandNews(String urlKey) {
		return new CollectedNewsDto(
			"온디맨드 급등 기사", "테스트경제", "https://news.example.com/on-demand/" + urlKey, NOW.minusMinutes(5));
	}

	// newsCollector mock stub의 argThat 매칭에 쓴다 — 여러 종목이 섞이는 테스트(중복 방지·실패 격리)에서
	// 특정 종목에만 스텁을 건다.
	private static boolean matchesInstrumentId(Instrument candidate, Long instrumentId) {
		return candidate != null && instrumentId.equals(candidate.getId());
	}

	@Test
	@DisplayName("스냅샷 조회·σ 계산·근거 매칭·서술·저장이 실 협력자로 이어져 카드 1건이 생성된다")
	void createsOneCardEndToEndWhenTheMoveAndEvidenceBothExist() {
		givenEnoughSnapshotsWithARecentJump();
		MarketNewsItem news = givenMatchingNews();

		cryptoPriceMoveWatcher.watch();

		List<PriceMoveEvent> cards = priceMoveEventRepository.findAll();
		assertThat(cards).hasSize(1);
		PriceMoveEvent card = cards.get(0);
		assertThat(card.getInstrument().getId()).isEqualTo(instrument.getId());
		assertThat(card.getMarket()).isEqualTo(Market.CRYPTO);
		assertThat(card.getOccurredAt()).isEqualTo(NOW);
		assertThat(card.getOriginTradeDate()).isEqualTo(NOW.toLocalDate());
		assertThat(card.getDetectionScore().doubleValue()).isGreaterThan(0);

		List<PriceMoveEventSource> sources = priceMoveEventSourceRepository.findAllByPriceMoveEventIdIn(
			List.of(card.getId()));
		assertThat(sources).hasSize(1);
		assertThat(sources.get(0).getMarketNewsItem().getId()).isEqualTo(news.getId());
	}

	@Test
	@DisplayName("근거 기사가 없으면 변동이 있어도 카드가 생성되지 않는다")
	void createsNoCardWhenNoMatchingEvidenceExists() {
		givenEnoughSnapshotsWithARecentJump();
		// 근거 기사를 만들지 않는다.

		cryptoPriceMoveWatcher.watch();

		assertThat(priceMoveEventRepository.findAll()).isEmpty();
	}

	@Test
	@DisplayName("배치가 price_move_events·price_move_event_sources에만 쓰고 원장·읽기 전용 테이블은 그대로다")
	void neverWritesOutsideThePriceMoveTables() {
		givenEnoughSnapshotsWithARecentJump();
		givenMatchingNews();

		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		Map<String, Long> readOnlyBefore = rowCounts(READ_ONLY_TABLES);
		long cardsBefore = priceMoveEventRepository.count();
		long sourcesBefore = priceMoveEventSourceRepository.count();

		cryptoPriceMoveWatcher.watch();

		// 실제로 쓰기가 일어났는데도 나머지가 그대로여야 의미가 있다.
		assertThat(priceMoveEventRepository.count()).isGreaterThan(cardsBefore);
		assertThat(priceMoveEventSourceRepository.count()).isGreaterThan(sourcesBefore);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
	}

	// tasks-285.md 4번 항목 — 이하 다섯 테스트가 이슈 #285의 완료 조건(카드 생성 성공률 상승·중복 방지·
	// 종목별 실패 격리)을 실 협력자(NewsCollector mock + 실제 MySQL/Redis)로 종단 검증한다.

	@Test
	@DisplayName("근거 기사가 DB에 없어도 온디맨드 수집(ADR-0017) 후 카드가 생성된다 — 카드 생성 성공률 상승")
	void createsCardViaOnDemandCollectionWhenNoEvidenceExistsBeforehand() {
		givenEnoughSnapshotsWithARecentJump();
		CollectedNewsDto article = onDemandNews("success");
		when(newsCollector.collect(argThat(i -> matchesInstrumentId(i, instrument.getId())), any()))
			.thenReturn(List.of(article));
		// 근거 기사를 사전에 저장하지 않는다 — givenMatchingNews() 호출 없음.

		cryptoPriceMoveWatcher.watch();

		List<MarketNewsItem> savedNews = marketNewsItemRepository.findAll().stream()
			.filter(item -> item.getUrl().equals(article.url()))
			.filter(item -> item.getInstrument().getId().equals(instrument.getId()))
			.toList();
		assertThat(savedNews).as("온디맨드 수집으로 근거 기사가 market_news_items에 새로 저장돼야 한다").hasSize(1);

		List<PriceMoveEvent> cards = priceMoveEventRepository.findAll();
		assertThat(cards).hasSize(1);
		List<PriceMoveEventSource> sources = priceMoveEventSourceRepository.findAllByPriceMoveEventIdIn(
			List.of(cards.get(0).getId()));
		assertThat(sources).hasSize(1);
		assertThat(sources.get(0).getMarketNewsItem().getId()).isEqualTo(savedNews.get(0).getId());
	}

	@Test
	@DisplayName("온디맨드 수집을 시도했는데도 근거가 0건이면 카드를 만들지 않는다")
	void createsNoCardWhenCollectionStillFindsNoEvidence() {
		givenEnoughSnapshotsWithARecentJump();
		when(newsCollector.collect(argThat(i -> matchesInstrumentId(i, instrument.getId())), any()))
			.thenReturn(List.of());

		cryptoPriceMoveWatcher.watch();

		assertThat(priceMoveEventRepository.findAll()).isEmpty();
		// 완전히 건너뛴 것이 아니라 "찾아봤지만 없었다"임을 구분한다.
		verify(newsCollector, times(1)).collect(argThat(i -> matchesInstrumentId(i, instrument.getId())), any());
	}

	@Test
	@DisplayName("온디맨드로 저장된 기사를 이어지는 30분 배치가 다시 수집해도 중복 행이 생기지 않는다")
	void doesNotDuplicateArticleWhenTheRegularBatchCollectsTheSameArticleAfterwards() {
		givenEnoughSnapshotsWithARecentJump();
		CollectedNewsDto article = onDemandNews("dup");
		when(newsCollector.collect(argThat(i -> matchesInstrumentId(i, instrument.getId())), any()))
			.thenReturn(List.of(article));

		cryptoPriceMoveWatcher.watch();
		// 같은 newsCollector 스텁을 유지한 채 30분 배치(collectNews)를 이어서 호출한다.
		newsCollectionService.collectNews();

		List<MarketNewsItem> savedForUrl = marketNewsItemRepository.findAll().stream()
			.filter(item -> item.getUrl().equals(article.url()))
			.filter(item -> item.getInstrument().getId().equals(instrument.getId()))
			.toList();
		assertThat(savedForUrl).as("30분 배치가 같은 기사를 다시 가져와도 중복 저장되면 안 된다").hasSize(1);
	}

	@Test
	@DisplayName("한 종목의 온디맨드 수집이 실패해도 watch() 밖으로 예외가 새지 않고 다른 종목은 영향받지 않는다")
	void isolatesOnDemandCollectionFailureToTheFailingInstrumentOnly() {
		givenEnoughSnapshotsWithARecentJump(SYMBOL);
		Instrument secondInstrument = givenSecondCryptoInstrument();
		givenEnoughSnapshotsWithARecentJump(SYMBOL2);
		// 건강한 종목(instrument)은 근거 기사를 미리 저장해 둬 첫 매칭에서 바로 카드가 만들어지고 온디맨드
		// 수집을 아예 타지 않는다 — 실패 종목(secondInstrument)만 온디맨드 수집 경로를 태운다.
		givenMatchingNews();
		when(newsCollector.collect(argThat(i -> matchesInstrumentId(i, secondInstrument.getId())), any()))
			.thenThrow(new RuntimeException("네이버 검색 API 실패"));

		assertThatCode(() -> cryptoPriceMoveWatcher.watch()).doesNotThrowAnyException();

		// 실패한 종목도 온디맨드 수집 시도(스냅샷 조회·매칭 시도의 다음 단계)는 있었다.
		verify(newsCollector, times(1))
			.collect(argThat(i -> matchesInstrumentId(i, secondInstrument.getId())), any());
		List<PriceMoveEvent> cards = priceMoveEventRepository.findAll();
		assertThat(cards).hasSize(1);
		assertThat(cards.get(0).getInstrument().getId()).isEqualTo(instrument.getId());
	}

	@Test
	@DisplayName("온디맨드 수집이 market_news_items에 실제로 써도 원장 테이블은 그대로다")
	void ledgerTablesStayUnchangedWhenOnDemandCollectionActuallyWrites() {
		givenEnoughSnapshotsWithARecentJump();
		CollectedNewsDto article = onDemandNews("ledger");
		when(newsCollector.collect(argThat(i -> matchesInstrumentId(i, instrument.getId())), any()))
			.thenReturn(List.of(article));
		// 근거 기사를 사전에 저장하지 않는다 — 온디맨드 수집이 실제로 market_news_items에 쓰는 경로를 태운다.

		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		long newsBefore = marketNewsItemRepository.count();

		cryptoPriceMoveWatcher.watch();

		assertThat(marketNewsItemRepository.count()).isGreaterThan(newsBefore);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
	}

	private Map<String, Long> rowCounts(List<String> tables) {
		entityManager.flush();
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : tables) {
			counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		return counts;
	}

}
