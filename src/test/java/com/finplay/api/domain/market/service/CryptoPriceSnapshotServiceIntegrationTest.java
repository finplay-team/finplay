package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.FeedConnectionStatus;
import com.finplay.api.domain.market.store.PriceStore;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class CryptoPriceSnapshotServiceIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 12, 1, 0);

	private static final String SYMBOL = "SNAPINV";

	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	@Autowired
	private CryptoPriceSnapshotService cryptoPriceSnapshotService;

	@Autowired
	private InstrumentRepository instrumentRepository;

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

	@BeforeEach
	void setUp() {
		clock.set(NOW);
		instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, SYMBOL, "테스트코인", BigDecimal.ONE, 5000L, true, NOW));
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(SYMBOL, new BigDecimal("100"), NOW.minusSeconds(1));
	}

	@AfterEach
	void tearDown() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		redisTemplate.delete("price:crypto:" + SYMBOL);
		redisTemplate.delete("price:crypto:" + SYMBOL + ":snapshots");
	}

	@Test
	@DisplayName("매분 기록은 Redis에 실제로 적재되지만 원장 테이블은 전혀 건드리지 않는다")
	void neverWritesLedgerTablesWhenRecordingSnapshots() {
		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);

		cryptoPriceSnapshotService.recordSnapshots();

		assertThat(cryptoPriceSnapshotService.getSnapshots(SYMBOL, NOW.minusMinutes(1), NOW))
			.singleElement()
			.satisfies(snapshot -> assertThat(snapshot.price()).isEqualByComparingTo("100"));
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
