// 실습 진행·의도 스키마와 MySQL upsert 및 JPA 영속화를 검증하는 슬라이스 테스트다.
package com.finplay.api.education.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.education.domain.PracticeIntention;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
	private PracticeIntentionRepository intentionRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private EntityManager entityManager;
	private User user;
	private Instrument instrument;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("practice-repo@finplay.com", "hash", "practice-repo", NOW));
		instrument = instrumentRepository.saveAndFlush(Instrument.create(
			Market.STOCK, "EDU175", "실습 종목", BigDecimal.ONE, 1L, true, NOW));
	}

	@Test
	void v15CreatesRequiredColumnsForeignKeysUniqueAndDecimalDefinitions() {
		assertThat(columns("practice_progresses")).containsExactly(
			"id:bigint:19:0", "user_id:bigint:19:0", "tutorial_key:varchar:50:0",
			"status:varchar:20:0", "started_at:datetime:0:6", "completed_at:datetime:0:6");
		assertThat(columns("practice_intentions")).containsExactly(
			"id:bigint:19:0", "user_id:bigint:19:0", "instrument_id:bigint:19:0",
			"quantity:decimal:30:8", "stop_loss:decimal:18:8", "take_profit:decimal:18:8",
			"created_at:datetime:0:6");
		assertThat(foreignKeys("practice_progresses"))
			.containsExactly("fk_practice_progresses_user->users->id");
		assertThat(foreignKeys("practice_intentions")).containsExactly(
			"fk_practice_intentions_instrument->instruments->id",
			"fk_practice_intentions_user->users->id");
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
	void intentionPersistsExactDecimalValuesAndAssociations() {
		PracticeIntention saved = intentionRepository.saveAndFlush(PracticeIntention.create(
			user, instrument, new BigDecimal("1234567890123456789012.12345678"),
			new BigDecimal("1234567890.12345678"), new BigDecimal("9999999999.99999999"), NOW));
		entityManager.clear();

		PracticeIntention found = intentionRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getUser().getId()).isEqualTo(user.getId());
		assertThat(found.getInstrument().getId()).isEqualTo(instrument.getId());
		assertThat(found.getQuantity()).isEqualByComparingTo("1234567890123456789012.12345678");
		assertThat(found.getStopLoss()).isEqualByComparingTo("1234567890.12345678");
		assertThat(found.getTakeProfit()).isEqualByComparingTo("9999999999.99999999");
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
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
