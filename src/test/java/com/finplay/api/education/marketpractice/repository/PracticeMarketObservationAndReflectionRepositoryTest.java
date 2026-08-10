// V27 마이그레이션이 만든 practice_market_observations/practice_market_reflections의 FK·UNIQUE·nullable 제약을 검증하는 슬라이스 테스트다.
package com.finplay.api.education.marketpractice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.education.marketpractice.domain.PracticeBoundary;
import com.finplay.api.education.marketpractice.domain.PracticeEvidenceType;
import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
import com.finplay.api.education.marketpractice.domain.PracticeMarketReflection;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import jakarta.persistence.EntityManager;
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
class PracticeMarketObservationAndReflectionRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 10, 0);

	@Autowired
	private PracticeMarketObservationRepository observationRepository;
	@Autowired
	private PracticeMarketReflectionRepository reflectionRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private EntityManager entityManager;

	private User user;
	private Holding holding;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(
			User.create("market-practice-repo@finplay.com", "hash", "market-practice-repo", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		// V7 마이그레이션 시드 데이터가 "005930" 등 실제 종목 심볼을 이미 선점하고 있어(uk_instruments_symbol),
		// 충돌하지 않는 테스트 전용 심볼을 쓴다.
		com.finplay.api.market.domain.Instrument instrument = instrumentRepository.saveAndFlush(
			com.finplay.api.market.domain.Instrument.create(
				com.finplay.api.market.domain.Market.STOCK, "MKTPRAC1", "실습전용종목", new BigDecimal("100"), 0L, true,
				NOW));
		holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));
	}

	@Test
	void observationsTableHasForeignKeysToUsersAndHoldings() {
		assertThat(foreignKeys("practice_market_observations")).containsExactlyInAnyOrder(
			"fk_practice_market_observations_user->users->id",
			"fk_practice_market_observations_holding->holdings->id");
	}

	@Test
	void reflectionsTableHasForeignKeysToUsersAndHoldingsAndUniqueUserTutorialConstraint() {
		assertThat(foreignKeys("practice_market_reflections")).containsExactlyInAnyOrder(
			"fk_practice_market_reflections_user->users->id",
			"fk_practice_market_reflections_holding->holdings->id");
		assertThat(indexColumns("practice_market_reflections", "uk_practice_market_reflections_user_tutorial"))
			.containsExactly("user_id", "tutorial_key");
	}

	@Test
	void observationNullableColumnsAcceptNullWhenEvidenceNotYetDetermined() {
		PracticeMarketObservation observation = PracticeMarketObservation.create(
			user.getId(), holding, holding.getInstrument().getId(), new BigDecimal("100.00000000"),
			null, null, null, NOW);

		PracticeMarketObservation saved = observationRepository.saveAndFlush(observation);
		entityManager.clear();

		PracticeMarketObservation reloaded = observationRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getCloserToBoundary()).isNull();
		assertThat(reloaded.getCloserBoundary()).isNull();
		assertThat(reloaded.getEvidenceType()).isNull();
	}

	@Test
	void observationNullableColumnsPersistValuesWhenEvidenceIsDetermined() {
		PracticeMarketObservation observation = PracticeMarketObservation.create(
			user.getId(), holding, holding.getInstrument().getId(), new BigDecimal("95.00000000"),
			true, PracticeBoundary.STOP_LOSS, PracticeEvidenceType.CLOSER_TO_BOUNDARY, NOW);

		PracticeMarketObservation saved = observationRepository.saveAndFlush(observation);
		entityManager.clear();

		PracticeMarketObservation reloaded = observationRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getCloserToBoundary()).isTrue();
		assertThat(reloaded.getCloserBoundary()).isEqualTo(PracticeBoundary.STOP_LOSS);
		assertThat(reloaded.getEvidenceType()).isEqualTo(PracticeEvidenceType.CLOSER_TO_BOUNDARY);
	}

	@Test
	void observationRejectsNullUserIdOrHoldingOrCurrentPriceOrObservedAt() {
		assertThatThrownBy(() -> observationRepository.saveAndFlush(
			PracticeMarketObservation.create(null, holding, holding.getInstrument().getId(),
				new BigDecimal("100"), null, null, null, NOW)))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	void reflectionRejectsSecondRowForSameUserAndTutorialKeyDueToUniqueConstraint() {
		PracticeMarketReflection first = PracticeMarketReflection.create(
			user.getId(), holding, "INVESTMENT_PRACTICE_V1", (short)1, "첫 복기", NOW);
		reflectionRepository.saveAndFlush(first);

		PracticeMarketReflection duplicate = PracticeMarketReflection.create(
			user.getId(), holding, "INVESTMENT_PRACTICE_V1", (short)1, "중복 복기 시도", NOW.plusMinutes(1));

		assertThatThrownBy(() -> reflectionRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void reflectionAllowsSameUserWithDifferentTutorialKey() {
		PracticeMarketReflection stockReflection = PracticeMarketReflection.create(
			user.getId(), holding, "INVESTMENT_PRACTICE_V1", (short)1, "주식 복기", NOW);
		PracticeMarketReflection coinReflection = PracticeMarketReflection.create(
			user.getId(), holding, "COIN_PRACTICE_V1", (short)1, "코인 복기", NOW.plusMinutes(1));

		reflectionRepository.saveAndFlush(stockReflection);
		reflectionRepository.saveAndFlush(coinReflection);
		entityManager.clear();

		assertThat(reflectionRepository.findByUserIdAndTutorialKey(user.getId(), "INVESTMENT_PRACTICE_V1"))
			.isPresent();
		assertThat(reflectionRepository.findByUserIdAndTutorialKey(user.getId(), "COIN_PRACTICE_V1"))
			.isPresent();
	}

	@Test
	void reflectionRejectsNullAnswer() {
		assertThatThrownBy(() -> reflectionRepository.saveAndFlush(
			PracticeMarketReflection.create(user.getId(), holding, "INVESTMENT_PRACTICE_V1", (short)1, null, NOW)))
			.isInstanceOf(RuntimeException.class);
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
