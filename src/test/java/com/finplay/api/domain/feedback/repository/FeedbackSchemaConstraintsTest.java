// V13이 만든 일곱 테이블의 컬럼별 NULL 허용 여부와 기본값을 information_schema로 직접 대조하는 JPA 슬라이스 테스트다.
package com.finplay.api.domain.feedback.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <b>왜 엔티티가 아니라 스키마를 보는가.</b> {@code ddl-auto=validate}는 컬럼 존재와 타입만 검사하고 <b>NULL 허용
 * 여부와 기본값은 보지 않는다.</b> 저장을 시도하는 리포지토리 테스트가 NULL 축을 실제로 검증하기는 하지만
 * (INSERT가 DB까지 나간다 — {@code spring-boot-starter-validation}이 있어 Hibernate가
 * {@code check_nullability}를 끈다) 컬럼마다 다 만들지는 않는다. <b>일곱 테이블 전 컬럼</b>을 한자리에서 고정하는
 * 것이 이 클래스의 몫이다. DDL 기본값은 JPA가 INSERT에 컬럼을 항상 명시해 저장 경로로는 확인할 수 없어 더욱 그렇다.
 *
 * <p>기대값의 정본은 spec §C-8 타입표다 (V13은 이 테스트의 검증 대상이라 정본이 될 수 없다). 컬럼을 추가·변경하면
 * 이 테스트가 먼저 깨지도록 <b>맵 전체를 비교</b>한다 — 새 컬럼이 조용히 섞여 들어오는 것을 막는 것이 목적이라
 * 부분 비교로 느슨하게 두지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class FeedbackSchemaConstraintsTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** 컬럼명 → {@code IS_NULLABLE}("NO"면 NOT NULL). */
	private Map<String, String> nullabilityOf(String table) {
		Map<String, String> columns = new LinkedHashMap<>();
		jdbcTemplate.query(
			"select column_name, is_nullable from information_schema.columns "
				+ "where table_schema = database() and table_name = ? order by ordinal_position",
			rs -> {
				columns.put(rs.getString("column_name"), rs.getString("is_nullable"));
			},
			table);
		return columns;
	}

	private static Map<String, String> expected(String... columnAndNullable) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < columnAndNullable.length; i += 2) {
			map.put(columnAndNullable[i], columnAndNullable[i + 1]);
		}
		return map;
	}

	@Test
	@DisplayName("market_news_items는 모든 컬럼이 NOT NULL이다 — 본문 없이 제목·언론사·URL·발행시각만 담는다")
	void marketNewsItemsColumnsAreAllNotNull() {
		assertThat(nullabilityOf("market_news_items")).isEqualTo(expected(
			"id", "NO",
			"instrument_id", "NO",
			"type", "NO",
			"title", "NO",
			"publisher", "NO",
			"url", "NO",
			"published_at", "NO",
			"created_at", "NO"));
	}

	@Test
	@DisplayName("price_move_events는 주식·코인이 나눠 쓰는 시각 컬럼만 NULL 허용이다")
	void priceMoveEventsNullableColumnsAreOnlyTheMarketSpecificOnes() {
		// window_start·window_end는 주식 전용, occurred_at은 코인 전용, reveal_time은 코인이 NULL이다 (§C-9).
		// 이 넷이 NOT NULL이면 두 형태 중 한쪽이 저장되지 않는다.
		assertThat(nullabilityOf("price_move_events")).isEqualTo(expected(
			"id", "NO",
			"instrument_id", "NO",
			"market", "NO",
			"event_type", "NO",
			"origin_trade_date", "NO",
			"window_start", "YES",
			"window_end", "YES",
			"occurred_at", "YES",
			"change_rate", "NO",
			"detection_score", "NO",
			"narrative", "YES",
			"narrative_source", "NO",
			"reveal_time", "YES",
			"created_at", "NO"));
	}

	@Test
	@DisplayName("price_move_event_sources는 연결 양쪽이 NOT NULL이다")
	void priceMoveEventSourcesColumnsAreAllNotNull() {
		assertThat(nullabilityOf("price_move_event_sources")).isEqualTo(expected(
			"id", "NO",
			"price_move_event_id", "NO",
			"market_news_item_id", "NO"));
	}

	@Test
	@DisplayName("instrument_news_summaries는 origin_trade_date가 NOT NULL이고 summary만 NULL 허용이다")
	void instrumentNewsSummariesKeepOriginTradeDateNotNull() {
		// origin_trade_date가 NULL 허용이면 MySQL 유니크가 중복을 허용해 코인 배치의 UPSERT가 갱신이 아니라
		// 새 행을 매시 쌓는다 (§C-9). 엔티티의 @Column(nullable = false)는 SQL을 내보내지 않아 이걸 못 잡는다.
		assertThat(nullabilityOf("instrument_news_summaries")).isEqualTo(expected(
			"id", "NO",
			"instrument_id", "NO",
			"origin_trade_date", "NO",
			"scope", "NO",
			"summary", "YES",
			"narrative_source", "NO",
			"generated_at", "NO"));
	}

	@Test
	@DisplayName("market_briefings도 origin_trade_date가 NOT NULL이고 summary만 NULL 허용이다")
	void marketBriefingsKeepOriginTradeDateNotNull() {
		assertThat(nullabilityOf("market_briefings")).isEqualTo(expected(
			"id", "NO",
			"market", "NO",
			"origin_trade_date", "NO",
			"summary", "YES",
			"narrative_source", "NO",
			"generated_at", "NO"));
	}

	@Test
	@DisplayName("price_move_peer_stats는 median_minutes_to_sell만 NULL 허용이다 — 전원 미매도를 0과 구분한다")
	void priceMovePeerStatsAllowNullOnlyForTheMedian() {
		assertThat(nullabilityOf("price_move_peer_stats")).isEqualTo(expected(
			"id", "NO",
			"price_move_event_id", "NO",
			"service_date", "NO",
			"holder_count", "NO",
			"sold_within_30min_count", "NO",
			"median_minutes_to_sell", "YES",
			"aggregated_at", "NO"));
	}

	@Test
	@DisplayName("trade_feedbacks는 narrative와 journal_fingerprint만 NULL 허용이다")
	void tradeFeedbacksAllowNullOnlyForTheNarrativeAndJournalFingerprint() {
		// journal_fingerprint의 NULL은 누락이 아니라 "그때 일기가 없었음"이라는 값이다 (§C-8, §FEED-013 결정 3).
		// NOT NULL로 승격하면 NULL → 값 전이가 사라져 일기를 나중에 쓴 체결에서 재생성이 열리지 않는다.
		assertThat(nullabilityOf("trade_feedbacks")).isEqualTo(expected(
			"id", "NO",
			"trade_id", "NO",
			"narrative", "YES",
			"narrative_source", "NO",
			"narrative_finalized", "NO",
			"regeneration_attempts", "NO",
			"journal_fingerprint", "YES",
			"journal_regenerations", "NO",
			"generated_at", "NO"));
	}

	@Test
	@DisplayName("trade_feedbacks의 narrative_finalized·regeneration_attempts·journal_regenerations에 DDL 기본값이 걸려 있다")
	void tradeFeedbacksCarryTheirDdlDefaults() {
		// 엔티티 팩토리가 세 컬럼을 항상 INSERT에 포함하므로 이 DEFAULT는 애플리케이션 경로에서 한 번도 쓰이지
		// 않는다 — 즉 저장·조회 테스트로는 존재를 확인할 수 없다. §C-8이 기본값을 명시했으므로 DDL에서 본다.
		// journal_regenerations는 여기에 DEFAULT가 없으면 V37이 기존 행을 채우지 못해 마이그레이션 자체가 깨진다.
		Map<String, Object> defaults = new LinkedHashMap<>();
		jdbcTemplate.query(
			"select column_name, column_default from information_schema.columns "
				+ "where table_schema = database() and table_name = 'trade_feedbacks' "
				+ "and column_name in ('narrative_finalized', 'regeneration_attempts', "
				+ "'journal_regenerations')",
			rs -> {
				defaults.put(rs.getString("column_name"), rs.getString("column_default"));
			});

		assertThat(defaults).hasSize(3);
		assertThat(defaults.get("narrative_finalized")).isEqualTo("0");
		assertThat(defaults.get("regeneration_attempts")).isEqualTo("0");
		assertThat(defaults.get("journal_regenerations")).isEqualTo("0");
	}
}
