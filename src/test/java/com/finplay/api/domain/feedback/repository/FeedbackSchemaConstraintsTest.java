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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class FeedbackSchemaConstraintsTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

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
