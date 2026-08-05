// 고정 Clock + Testcontainers(MySQL·Redis)로 CryptoPriceSnapshotService의 매분 기록이 Redis에만 쓰고 원장 테이블을 건드리지 않는지 검증한다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// tasks.md 5번 항목(8개 이슈 공통 조건 — 원장 불변)의 세 몫 중 CryptoPriceSnapshotService의 매분 기록을 맡는다.
// 나머지 둘(CryptoPriceMoveWatcher·PriceMoveQueryService 코인 분기)은 CryptoPriceMoveWatcherIntegrationTest·
// PriceMoveQueryGateIntegrationTest가 각각 맡는다 — 세 파일이 spec.md 완료 조건 10번을 나눠 채운다.
//
// 이 스케줄은 Redis에만 쓴다(§코인 가격 스냅샷) — 원장(orders·trades·accounts·holdings·holding_lots·
// trade_allocations)을 읽지도 않으므로 값이 바뀔 UPDATE 경로 자체가 없다. 행 수 비교로 충분하다
// (docs/agent-mistakes.md 2026-08-04 "원장 불변" 행의 값 비교 요구는 UPDATE가 가능한 경로에만 해당한다).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, CryptoPriceSnapshotServiceIntegrationTest.FixedClockTestConfig.class})
class CryptoPriceSnapshotServiceIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 12, 1, 0);

	private static final String SYMBOL = "SNAPINV";

	// 다른 원장 불변 테스트(CryptoPriceMoveWatcherIntegrationTest 등)와 같은 테이블 목록이다.
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

	@BeforeEach
	void setUp() {
		instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, SYMBOL, "테스트코인", BigDecimal.ONE, 5000L, true, NOW));
		// isPriceAvailable=true가 되도록 최신 틱을 채운다 — 실제로 기록이 일어나는 것을 확인해야 "원장은 그대로다"가
		// 의미를 갖는다(아무것도 안 쓰면 통과가 공허하다).
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(SYMBOL, new BigDecimal("100"), NOW.minusSeconds(1));
	}

	@AfterEach
	void tearDown() {
		// 다른 테스트의 기본 전제(CONNECTED)를 되돌려 놓는다(OrderBuyIntegrationTest 선례) — Redis는 JPA 트랜잭션
		// 롤백 대상이 아니라 직접 정리해야 한다.
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		redisTemplate.delete("price:crypto:" + SYMBOL);
		redisTemplate.delete("price:crypto:" + SYMBOL + ":snapshots");
	}

	@Test
	@DisplayName("매분 기록은 Redis에 실제로 적재되지만 원장 테이블은 전혀 건드리지 않는다")
	void neverWritesLedgerTablesWhenRecordingSnapshots() {
		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);

		cryptoPriceSnapshotService.recordSnapshots();

		// 실제로 Redis에 쓰기가 일어났는데도 원장이 그대로여야 의미가 있다.
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

	@TestConfiguration
	static class FixedClockTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
		}
	}
}
