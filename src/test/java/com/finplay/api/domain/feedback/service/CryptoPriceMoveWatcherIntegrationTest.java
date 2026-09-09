package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
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
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
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

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class CryptoPriceMoveWatcherIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0);

	private static final String SYMBOL = "MOVEWATCH";

	private static final String SYMBOL2 = "MOVEWATCH2";

	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	private static final List<String> READ_ONLY_TABLES = List.of("instruments", "market_news_items");

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

	private void givenEnoughSnapshotsWithARecentJump() {
		givenEnoughSnapshotsWithARecentJump(SYMBOL);
	}

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

	private static CollectedNewsDto onDemandNews(String urlKey) {
		return new CollectedNewsDto(
			"온디맨드 급등 기사", "테스트경제", "https://news.example.com/on-demand/" + urlKey, NOW.minusMinutes(5));
	}

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

		assertThat(priceMoveEventRepository.count()).isGreaterThan(cardsBefore);
		assertThat(priceMoveEventSourceRepository.count()).isGreaterThan(sourcesBefore);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
	}


	@Test
	@DisplayName("근거 기사가 DB에 없어도 온디맨드 수집(ADR-0017) 후 카드가 생성된다 — 카드 생성 성공률 상승")
	void createsCardViaOnDemandCollectionWhenNoEvidenceExistsBeforehand() {
		givenEnoughSnapshotsWithARecentJump();
		CollectedNewsDto article = onDemandNews("success");
		when(newsCollector.collect(argThat(i -> matchesInstrumentId(i, instrument.getId())), any()))
			.thenReturn(List.of(article));

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
		givenMatchingNews();
		when(newsCollector.collect(argThat(i -> matchesInstrumentId(i, secondInstrument.getId())), any()))
			.thenThrow(new RuntimeException("네이버 검색 API 실패"));

		assertThatCode(() -> cryptoPriceMoveWatcher.watch()).doesNotThrowAnyException();

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
