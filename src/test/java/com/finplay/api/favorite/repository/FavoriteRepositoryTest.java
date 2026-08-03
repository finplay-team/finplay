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
		Integer columnCount = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM information_schema.columns
			WHERE table_schema = DATABASE() AND table_name = 'favorites'
			  AND column_name IN ('id', 'user_id', 'instrument_id', 'created_at')
			""", Integer.class);
		Integer foreignKeyCount = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM information_schema.referential_constraints
			WHERE constraint_schema = DATABASE() AND table_name = 'favorites'
			  AND constraint_name IN ('fk_favorites_user', 'fk_favorites_instrument')
			""", Integer.class);
		Integer uniqueColumnCount = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM information_schema.statistics
			WHERE table_schema = DATABASE() AND table_name = 'favorites'
			  AND index_name = 'uk_favorites_user_instrument' AND non_unique = 0
			""", Integer.class);
		Integer orderedIndexColumns = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM information_schema.statistics
			WHERE table_schema = DATABASE() AND table_name = 'favorites'
			  AND index_name = 'idx_favorites_user_created_id'
			  AND column_name IN ('user_id', 'created_at', 'id')
			""", Integer.class);

		assertThat(columnCount).isEqualTo(4);
		assertThat(foreignKeyCount).isEqualTo(2);
		assertThat(uniqueColumnCount).isEqualTo(2);
		assertThat(orderedIndexColumns).isEqualTo(3);
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
