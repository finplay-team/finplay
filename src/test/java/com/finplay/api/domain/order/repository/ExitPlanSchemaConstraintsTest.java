// V35가 만든 세 테이블의 컬럼 타입·NULL 허용 여부와 unique·FK 구성을 information_schema로 직접 대조하는 슬라이스 테스트다.
package com.finplay.api.domain.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * <b>왜 엔티티가 아니라 스키마를 보는가.</b> {@code ddl-auto=validate}는 컬럼 존재와 타입만 검사하고 NULL 허용 여부,
 * DECIMAL 정밀도, unique·FK 구성은 보지 않는다. 이 클래스가 021 plan.md "데이터 모델" 표를 물리 스키마에 고정한다.
 * (엔티티 ↔ V35 정합성 자체는 이 테스트 컨텍스트가 {@code validate}로 기동하는 것으로 확인된다 — 매핑이 어긋나면
 * 컨텍스트 로딩 단계에서 실패한다.)
 *
 * <p>컬럼이 조용히 추가·변경되면 먼저 깨지도록 <b>맵 전체를 비교</b>한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class ExitPlanSchemaConstraintsTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("exit_plans의 NULL 허용 여부가 plan.md 데이터 모델 표와 일치한다 — holding_id는 두 경로 공통 NOT NULL이다")
	void exitPlansNullabilityMatchesPlannedDataModel() {
		assertThat(nullabilityOf("exit_plans")).isEqualTo(expected(
			"id", "NO",
			"user_id", "NO",
			"holding_id", "NO",
			"intention_id", "YES",
			"intention_instance_key", "YES",
			"buy_trade_id", "YES",
			"instrument_id", "NO",
			"quantity", "NO",
			"entry_price", "NO",
			"exit_price_type", "NO",
			"stop_loss_rate", "YES",
			"take_profit_rate", "YES",
			"stop_loss_price", "NO",
			"take_profit_price", "NO",
			"baseline_price", "NO",
			"baseline_observed_at", "NO",
			"status", "NO",
			"reserved_at", "NO",
			"closed_at", "YES",
			"triggered_order_id", "YES",
			"replay_session_id", "YES",
			"practice_attempt_id", "YES",
			"practice_attempt_run_number", "YES",
			"request_hash", "NO"));
	}

	@Test
	@DisplayName("exit_plans의 숫자·문자 컬럼 타입이 plan.md 표의 정밀도 그대로다")
	void exitPlansColumnTypesMatchPlannedPrecision() {
		Map<String, String> types = columnTypesOf("exit_plans");

		assertThat(types).containsAllEntriesOf(expected(
			"quantity", "decimal(30,8)",
			"entry_price", "decimal(18,8)",
			"stop_loss_rate", "decimal(7,4)",
			"take_profit_rate", "decimal(8,4)",
			"stop_loss_price", "decimal(18,8)",
			"take_profit_price", "decimal(18,8)",
			"baseline_price", "decimal(18,8)",
			"exit_price_type", "varchar(10)",
			"status", "varchar(20)",
			"intention_instance_key", "char(36)",
			"request_hash", "char(64)"));
	}

	@Test
	@DisplayName("exit_plan_conditions의 NULL 허용 여부가 확정 스키마와 일치한다 — created_at은 NOT NULL이다")
	void exitPlanConditionsNullabilityMatchesSchema() {
		assertThat(nullabilityOf("exit_plan_conditions")).isEqualTo(expected(
			"id", "NO",
			"exit_plan_id", "NO",
			"condition_type", "NO",
			"trigger_price", "NO",
			"status", "NO",
			"created_at", "NO"));
		assertThat(columnTypesOf("exit_plan_conditions")).containsAllEntriesOf(expected(
			"condition_type", "varchar(20)",
			"trigger_price", "decimal(18,8)",
			"status", "varchar(20)"));
	}

	@Test
	@DisplayName("exit_plan_idempotency_keys의 NULL 허용 여부가 확정 스키마와 일치한다")
	void exitPlanIdempotencyKeysNullabilityMatchesSchema() {
		assertThat(nullabilityOf("exit_plan_idempotency_keys")).isEqualTo(expected(
			"id", "NO",
			"user_id", "NO",
			"idempotency_key", "NO",
			"request_hash", "NO",
			"exit_plan_id", "NO",
			"created_at", "NO"));
		assertThat(columnTypesOf("exit_plan_idempotency_keys")).containsAllEntriesOf(expected(
			"idempotency_key", "varchar(36)",
			"request_hash", "char(64)"));
	}

	@Test
	@DisplayName("exit_plans의 unique는 PK와 (user_id, intention_instance_key) 둘뿐이다 — (holding_id, status) 부분 unique는 두지 않는다")
	void exitPlansHasOnlyIntentionInstanceUniqueBesidesPrimaryKey() {
		assertThat(uniqueIndexesOf("exit_plans")).isEqualTo(expected(
			"PRIMARY", "id",
			"uk_exit_plans_user_intention_instance", "user_id,intention_instance_key"));
	}

	@Test
	// 클래스 javadoc이 선언한 대로 맵 전체를 비교한다 — 여분 인덱스가 조용히 생기는 것까지 잡는다.
	// idx_exit_plans_practice_attempt_run_status는 042 6번의 "현재 실행 세대의 PENDING 예약" 조회용이고,
	// 선두 컬럼이 practice_attempt_id라 fk_exit_plans_practice_attempt가 이 인덱스를 그대로 쓴다. FK 이름의
	// 단일 컬럼 인덱스가 목록에 없는 것이 인덱스를 FK보다 먼저 만든 이유다(다른 FK 넷에는 그 인덱스가 있다).
	@DisplayName("exit_plans의 인덱스 구성이 확정 스키마와 일치한다 — 튜토리얼 귀속 FK는 복합 인덱스를 재사용한다")
	void exitPlansIndexesMatchPlannedLookups() {
		assertThat(allIndexesOf("exit_plans")).isEqualTo(expected(
			"PRIMARY", "id",
			"uk_exit_plans_user_intention_instance", "user_id,intention_instance_key",
			"idx_exit_plans_holding_status", "holding_id,status",
			"idx_exit_plans_user_status", "user_id,status",
			"idx_exit_plans_practice_attempt_run_status",
			"practice_attempt_id,practice_attempt_run_number,status,id",
			"fk_exit_plans_buy_trade", "buy_trade_id",
			"fk_exit_plans_instrument", "instrument_id",
			"fk_exit_plans_triggered_order", "triggered_order_id",
			"fk_exit_plans_replay_session", "replay_session_id"));
	}

	@Test
	@DisplayName("exit_plans의 FK는 일곱 개이고 intention_id는 FK가 아니다 — 숫자 snapshot일 뿐이다")
	void exitPlansForeignKeysMatchPlannedReferences() {
		assertThat(foreignKeysOf("exit_plans")).isEqualTo(expected(
			"user_id", "users",
			"holding_id", "holdings",
			"buy_trade_id", "trades",
			"instrument_id", "instruments",
			"triggered_order_id", "orders",
			"replay_session_id", "stock_replay_sessions",
			"practice_attempt_id", "practice_attempts"));
	}

	@Test
	@DisplayName("exit_plan_conditions는 plan당 조건 유형 1건 unique와 plan FK를 갖는다")
	void exitPlanConditionsHasPlanTypeUniqueAndPlanForeignKey() {
		assertThat(uniqueIndexesOf("exit_plan_conditions")).isEqualTo(expected(
			"PRIMARY", "id",
			"uk_exit_plan_conditions_plan_type", "exit_plan_id,condition_type"));
		assertThat(foreignKeysOf("exit_plan_conditions")).isEqualTo(expected("exit_plan_id", "exit_plans"));
	}

	@Test
	@DisplayName("exit_plan_idempotency_keys는 (user_id, idempotency_key) unique와 user·plan FK를 갖는다")
	void exitPlanIdempotencyKeysHasUserKeyUniqueAndForeignKeys() {
		assertThat(uniqueIndexesOf("exit_plan_idempotency_keys")).isEqualTo(expected(
			"PRIMARY", "id",
			"uk_exit_plan_idempotency_keys_user_key", "user_id,idempotency_key"));
		assertThat(foreignKeysOf("exit_plan_idempotency_keys")).isEqualTo(expected(
			"user_id", "users",
			"exit_plan_id", "exit_plans"));
	}

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

	/** 컬럼명 → {@code COLUMN_TYPE}(예 {@code decimal(18,8)}). */
	private Map<String, String> columnTypesOf(String table) {
		Map<String, String> columns = new LinkedHashMap<>();
		jdbcTemplate.query(
			"select column_name, column_type from information_schema.columns "
				+ "where table_schema = database() and table_name = ? order by ordinal_position",
			rs -> {
				columns.put(rs.getString("column_name"), rs.getString("column_type").toLowerCase());
			},
			table);
		return columns;
	}

	/** unique 인덱스명 → 컬럼 목록(순서 유지). */
	private Map<String, String> uniqueIndexesOf(String table) {
		return indexesOf(table, true);
	}

	/** 모든 인덱스명 → 컬럼 목록(순서 유지). */
	private Map<String, String> allIndexesOf(String table) {
		return indexesOf(table, false);
	}

	private Map<String, String> indexesOf(String table, boolean uniqueOnly) {
		Map<String, String> indexes = new LinkedHashMap<>();
		String sql = "select index_name, group_concat(column_name order by seq_in_index) as columns "
			+ "from information_schema.statistics where table_schema = database() and table_name = ? "
			+ (uniqueOnly ? "and non_unique = 0 " : "")
			+ "group by index_name order by index_name";
		jdbcTemplate.query(
			sql,
			rs -> {
				indexes.put(rs.getString("index_name"), rs.getString("columns"));
			},
			table);
		return indexes;
	}

	/** FK 컬럼명 → 참조 테이블명. */
	private Map<String, String> foreignKeysOf(String table) {
		Map<String, String> foreignKeys = new LinkedHashMap<>();
		jdbcTemplate.query(
			"select column_name, referenced_table_name from information_schema.key_column_usage "
				+ "where table_schema = database() and table_name = ? and referenced_table_name is not null "
				+ "order by constraint_name",
			rs -> {
				foreignKeys.put(rs.getString("column_name"), rs.getString("referenced_table_name"));
			},
			table);
		return foreignKeys;
	}

	private static Map<String, String> expected(String... keyAndValue) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < keyAndValue.length; i += 2) {
			map.put(keyAndValue[i], keyAndValue[i + 1]);
		}
		return map;
	}

	@Test
	@DisplayName("V35가 만든 세 테이블이 모두 존재한다")
	void createsAllThreeExitPlanTables() {
		List<String> tables = jdbcTemplate.queryForList(
			"select table_name from information_schema.tables where table_schema = database() "
				+ "and table_name in ('exit_plans', 'exit_plan_conditions', 'exit_plan_idempotency_keys') "
				+ "order by table_name",
			String.class);

		assertThat(tables).containsExactly("exit_plan_conditions", "exit_plan_idempotency_keys", "exit_plans");
	}
}
