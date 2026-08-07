// 고정 Clock + Testcontainers(MySQL·Redis)로 코인 변동 감시(CryptoPriceMoveWatcher)의 종단을 검증한다 — 카드 생성과 원장 불변.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventSource;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.PriceStore;
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

	// 배치 실행 전후로 행이 변하면 안 되는 원장 테이블 (다른 원장 불변 테스트와 같은 목록).
	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	// 이 배치가 읽기만 해야 하는 테이블.
	private static final List<String> READ_ONLY_TABLES = List.of("instruments", "market_news_items");

	@Autowired
	private CryptoPriceMoveWatcher cryptoPriceMoveWatcher;

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
	}

	// min-sample-count(기본 100) 세그먼트를 실제로 채우는 5분 간격 스냅샷 101개 — ago 0~5분은 점프 이후,
	// 그 뒤(ago 5~500분)는 점프 이전이다. "5분 전 가격을 실제로 꺼내 쓴다"(완료 조건 1)가 이 픽스처의 핵심이다.
	private void givenEnoughSnapshotsWithARecentJump() {
		BigDecimal past = BigDecimal.valueOf(100);
		BigDecimal now = BigDecimal.valueOf(100 * Math.exp(0.12));
		Duration retention = Duration.ofHours(24);
		for (int agoMinutes = 500; agoMinutes >= 5; agoMinutes -= 5) {
			priceStore.recordSnapshot(SYMBOL, NOW.minusMinutes(agoMinutes), past, retention);
		}
		priceStore.recordSnapshot(SYMBOL, NOW, now, retention);
	}

	private MarketNewsItem givenMatchingNews() {
		return marketNewsItemRepository.save(MarketNewsItem.create(
			instrument, MarketNewsItemType.NEWS, "테스트 급등 기사", "테스트경제",
			"https://news.example.com/move-watch", NOW.minusMinutes(5), NOW));
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

	private Map<String, Long> rowCounts(List<String> tables) {
		entityManager.flush();
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : tables) {
			counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		return counts;
	}

}
