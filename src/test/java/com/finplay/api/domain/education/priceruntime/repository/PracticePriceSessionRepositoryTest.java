package com.finplay.api.domain.education.priceruntime.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSessionStatus;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class PracticePriceSessionRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 10, 0);

	@Autowired
	private PracticePriceSessionRepository practicePriceSessionRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private EntityManager entityManager;

	private User user;
	private Instrument instrument;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(
			User.create(uniqueEmail(), "hash", uniqueNickname(), NOW));
		String symbol = "PPS" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "테스트코인", new BigDecimal("0.00000001"), 0L, true, NOW));
	}

	@Test
	void rejectsSecondActiveSessionForSameUserAndInstrumentDueToActiveSlotUniqueConstraint() {
		PracticePriceSession first = PracticePriceSession.create(
			user.getId(), instrument.getId(), 1L, (short)1, new BigDecimal("10000.00000000"), NOW);
		practicePriceSessionRepository.saveAndFlush(first);

		PracticePriceSession second = PracticePriceSession.create(
			user.getId(), instrument.getId(), 2L, (short)1, new BigDecimal("20000.00000000"), NOW.plusMinutes(1));

		assertThatThrownBy(() -> practicePriceSessionRepository.saveAndFlush(second))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsCompletedSessionAndNewActiveSessionForSameUserAndInstrument() {
		PracticePriceSession completed = PracticePriceSession.create(
			user.getId(), instrument.getId(), 1L, (short)1, new BigDecimal("10000.00000000"), NOW);
		practicePriceSessionRepository.saveAndFlush(completed);
		entityManager.createNativeQuery(
			"UPDATE practice_price_sessions SET status = 'COMPLETED', completed_at = :completedAt WHERE id = :id")
			.setParameter("completedAt", NOW.plusMinutes(5))
			.setParameter("id", completed.getId())
			.executeUpdate();
		entityManager.clear();

		PracticePriceSession newActive = PracticePriceSession.create(
			user.getId(), instrument.getId(), 3L, (short)1, new BigDecimal("15000.00000000"), NOW.plusMinutes(6));
		PracticePriceSession saved = practicePriceSessionRepository.saveAndFlush(newActive);

		assertThat(saved.getId()).isNotNull();
		assertThat(practicePriceSessionRepository.existsByUserIdAndInstrumentIdAndStatus(
			user.getId(), instrument.getId(), PracticePriceSessionStatus.ACTIVE)).isTrue();
	}

	@Test
	void findByIdAndUserIdReturnsEmptyForAnotherOwner() {
		PracticePriceSession session = PracticePriceSession.create(
			user.getId(), instrument.getId(), 1L, (short)1, new BigDecimal("10000.00000000"), NOW);
		PracticePriceSession saved = practicePriceSessionRepository.saveAndFlush(session);

		assertThat(practicePriceSessionRepository.findByIdAndUserId(saved.getId(), user.getId() + 1))
			.isEmpty();
		assertThat(practicePriceSessionRepository.findByIdAndUserId(saved.getId(), user.getId()))
			.isPresent();
	}

	@Test
	void existsByUserIdAndInstrumentIdAndStatusIsFalseWhenNoSessionExists() {
		assertThat(practicePriceSessionRepository.existsByUserIdAndInstrumentIdAndStatus(
			user.getId(), instrument.getId(), PracticePriceSessionStatus.ACTIVE)).isFalse();
	}

	@Test
	void findByIdAndUserIdForUpdateReturnsSessionForOwner() {
		PracticePriceSession session = PracticePriceSession.create(
			user.getId(), instrument.getId(), 1L, (short)1, new BigDecimal("10000.00000000"), NOW);
		PracticePriceSession saved = practicePriceSessionRepository.saveAndFlush(session);

		assertThat(practicePriceSessionRepository.findByIdAndUserIdForUpdate(saved.getId(), user.getId()))
			.isPresent()
			.get()
			.satisfies(found -> assertThat(found.getId()).isEqualTo(saved.getId()));
	}

	@Test
	void findByIdAndUserIdForUpdateReturnsEmptyForAnotherOwner() {
		PracticePriceSession session = PracticePriceSession.create(
			user.getId(), instrument.getId(), 1L, (short)1, new BigDecimal("10000.00000000"), NOW);
		PracticePriceSession saved = practicePriceSessionRepository.saveAndFlush(session);

		assertThat(practicePriceSessionRepository.findByIdAndUserIdForUpdate(saved.getId(), user.getId() + 1))
			.isEmpty();
	}

	@Test
	void findByIdAndUserIdForUpdateReturnsEmptyWhenSessionDoesNotExist() {
		assertThat(practicePriceSessionRepository.findByIdAndUserIdForUpdate(999_999L, user.getId()))
			.isEmpty();
	}

	private static String uniqueEmail() {
		return "pps-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname() {
		return "pps-" + UUID.randomUUID().toString().replace("-", "");
	}
}
