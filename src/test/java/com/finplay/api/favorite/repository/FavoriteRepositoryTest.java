// 즐겨찾기 스키마의 FK·유일 제약·조회 인덱스를 실제 MySQL에서 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.favorite.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.favorite.domain.Favorite;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class FavoriteRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 3, 10, 0);
	@Autowired
	private FavoriteRepository favoriteRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	private User user;
	private Instrument instrument;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("favorite@finplay.com", "hash", "favorite-user", NOW));
		instrument = instrumentRepository.saveAndFlush(Instrument.create(
			Market.STOCK, "FAV163", "즐겨찾기 종목", BigDecimal.ONE, 1L, true, NOW));
	}

	@Test
	void favoritesTableHasRequiredColumnsForeignKeysUniqueConstraintAndIndex() {
		List<String> columns = jdbcTemplate.queryForList("""
			SELECT column_name FROM information_schema.columns
			WHERE table_schema = DATABASE() AND table_name = 'favorites'
			ORDER BY ordinal_position
			""", String.class);
		List<String> foreignKeys = jdbcTemplate.query("""
			SELECT constraint_name, referenced_table_name, referenced_column_name
			FROM information_schema.key_column_usage
			WHERE constraint_schema = DATABASE() AND table_name = 'favorites'
			  AND constraint_name IN ('fk_favorites_user', 'fk_favorites_instrument')
			ORDER BY constraint_name
			""", (resultSet, rowNumber) -> String.join("->",
			resultSet.getString("constraint_name"), resultSet.getString("referenced_table_name"),
			resultSet.getString("referenced_column_name")));
		List<String> uniqueColumns = jdbcTemplate.queryForList("""
			SELECT column_name FROM information_schema.statistics
			WHERE table_schema = DATABASE() AND table_name = 'favorites'
			  AND index_name = 'uk_favorites_user_instrument' AND non_unique = 0
			ORDER BY seq_in_index
			""", String.class);
		List<String> orderedIndexColumns = jdbcTemplate.queryForList("""
			SELECT column_name FROM information_schema.statistics
			WHERE table_schema = DATABASE() AND table_name = 'favorites'
			  AND index_name = 'idx_favorites_user_created_id'
			ORDER BY seq_in_index
			""", String.class);

		assertThat(columns).containsExactly("id", "user_id", "instrument_id", "created_at");
		assertThat(foreignKeys).containsExactly(
			"fk_favorites_instrument->instruments->id", "fk_favorites_user->users->id");
		assertThat(uniqueColumns).containsExactly("user_id", "instrument_id");
		assertThat(orderedIndexColumns).containsExactly("user_id", "created_at", "id");
	}

	@Test
	void saveAndFlushRejectsDuplicateUserAndInstrument() {
		favoriteRepository.saveAndFlush(Favorite.create(user, instrument, NOW));

		assertThatThrownBy(() -> favoriteRepository.saveAndFlush(Favorite.create(user, instrument, NOW.plusSeconds(1))))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void existsByUserIdAndInstrumentIdFindsOnlyMatchingPair() {
		favoriteRepository.saveAndFlush(Favorite.create(user, instrument, NOW));

		assertThat(favoriteRepository.existsByUserIdAndInstrumentId(user.getId(), instrument.getId())).isTrue();
		assertThat(favoriteRepository.existsByUserIdAndInstrumentId(user.getId(), instrument.getId() + 1)).isFalse();
	}
}
