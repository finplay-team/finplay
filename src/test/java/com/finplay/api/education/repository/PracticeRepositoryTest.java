// 실습 진행(practice_progresses) 스키마와 MySQL upsert를 검증하는 슬라이스 테스트다.
// practice_intentions은 #193(ADR-0012)에 따라 인메모리로 전환되고 테이블 자체가 V19 마이그레이션으로 삭제되어 제외한다.
package com.finplay.api.education.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class PracticeRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 4, 10, 0);
	@Autowired
	private PracticeProgressRepository progressRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private EntityManager entityManager;
	private User user;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("practice-repo@finplay.com", "hash", "practice-repo", NOW));
	}

	@Test
	void v15CreatesRequiredColumnsForeignKeyAndUniqueConstraint() {
		assertThat(columns("practice_progresses")).containsExactly(
			"id:bigint:19:0", "user_id:bigint:19:0", "tutorial_key:varchar:50:0",
			"status:varchar:20:0", "started_at:datetime:0:6", "completed_at:datetime:0:6");
		assertThat(foreignKeys("practice_progresses"))
			.containsExactly("fk_practice_progresses_user->users->id");
		assertThat(indexColumns("practice_progresses", "uk_practice_progresses_user_tutorial"))
			.containsExactly("user_id", "tutorial_key");
	}

	@Test
	void insertIfAbsentPreservesExistingCompletedStatusAndTimestamps() {
		progressRepository.insertIfAbsent(user.getId(), PracticeIntentionService.TUTORIAL_KEY, NOW);
		jdbcTemplate.update("""
			UPDATE practice_progresses SET status = 'COMPLETED', started_at = ?, completed_at = ?
			WHERE user_id = ? AND tutorial_key = ?
			""", NOW.minusDays(1), NOW.minusHours(1), user.getId(), PracticeIntentionService.TUTORIAL_KEY);

		progressRepository.insertIfAbsent(user.getId(), PracticeIntentionService.TUTORIAL_KEY, NOW.plusDays(1));
		entityManager.flush();
		entityManager.clear();

		Map<String, Object> row = jdbcTemplate.queryForMap("""
			SELECT status, started_at, completed_at FROM practice_progresses
			WHERE user_id = ? AND tutorial_key = ?
			""", user.getId(), PracticeIntentionService.TUTORIAL_KEY);
		assertThat(row.get("status")).isEqualTo("COMPLETED");
		assertThat(row.get("started_at")).isEqualTo(NOW.minusDays(1));
		assertThat(row.get("completed_at")).isEqualTo(NOW.minusHours(1));
	}

	@Test
	void insertIfAbsentForStockAndCoinKeysCreatesTwoIndependentRowsForSameUser() {
		progressRepository.insertIfAbsent(user.getId(), PracticeIntentionService.TUTORIAL_KEY, NOW);
		progressRepository.insertIfAbsent(user.getId(), PracticeIntentionService.COIN_TUTORIAL_KEY, NOW);
		jdbcTemplate.update("""
			UPDATE practice_progresses SET status = 'COMPLETED', completed_at = ?
			WHERE user_id = ? AND tutorial_key = ?
			""", NOW, user.getId(), PracticeIntentionService.TUTORIAL_KEY);
		entityManager.flush();
		entityManager.clear();

		List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
			SELECT tutorial_key, status FROM practice_progresses WHERE user_id = ? ORDER BY tutorial_key
			""", user.getId());

		assertThat(rows).hasSize(2);
		assertThat(rows).extracting(row -> row.get("tutorial_key"))
			.containsExactlyInAnyOrder(PracticeIntentionService.TUTORIAL_KEY,
				PracticeIntentionService.COIN_TUTORIAL_KEY);
		// 주식 key만 COMPLETED로 갱신했으므로 코인 key 행은 독립적으로 IN_PROGRESS를 유지해야 한다(회귀 없음).
		Map<String, String> statusByKey = new HashMap<>();
		rows.forEach(row -> statusByKey.put((String)row.get("tutorial_key"), (String)row.get("status")));
		assertThat(statusByKey.get(PracticeIntentionService.TUTORIAL_KEY)).isEqualTo("COMPLETED");
		assertThat(statusByKey.get(PracticeIntentionService.COIN_TUTORIAL_KEY)).isEqualTo("IN_PROGRESS");
	}

	private List<String> columns(String table) {
		return jdbcTemplate.query("""
			SELECT column_name, data_type, COALESCE(numeric_precision, character_maximum_length, 0) size,
			       COALESCE(numeric_scale, datetime_precision, 0) scale
			FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?
			ORDER BY ordinal_position
			""", (rs, row) -> String.join(":", rs.getString("column_name"), rs.getString("data_type"),
			rs.getString("size"), rs.getString("scale")), table);
	}

	private List<String> foreignKeys(String table) {
		return jdbcTemplate.query("""
			SELECT constraint_name, referenced_table_name, referenced_column_name
			FROM information_schema.key_column_usage
			WHERE constraint_schema = DATABASE() AND table_name = ? AND referenced_table_name IS NOT NULL
			ORDER BY constraint_name
			""", (rs, row) -> String.join("->", rs.getString("constraint_name"),
			rs.getString("referenced_table_name"), rs.getString("referenced_column_name")), table);
	}

	private List<String> indexColumns(String table, String index) {
		return jdbcTemplate.queryForList("""
			SELECT column_name FROM information_schema.statistics
			WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? AND non_unique = 0
			ORDER BY seq_in_index
			""", String.class, table, index);
	}
}
