// reauth_tokens 저장과 token_hash UNIQUE·user_id FK 제약을 실제 MySQL로 검증하는 슬라이스 테스트다.
package com.finplay.api.domain.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.ReauthToken;
import com.finplay.api.domain.auth.entity.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class ReauthTokenRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 10, 30, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ReauthTokenRepository reauthTokenRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void saveStoresHashExpiryAndUnconsumedState() {
		User user = saveUser("reauth-save@finplay.com", "reauth-save-user");

		ReauthToken saved = reauthTokenRepository.saveAndFlush(
			ReauthToken.create(user, "token-hash-value", NOW.plusMinutes(5), NOW));

		ReauthToken found = reauthTokenRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getUser().getId()).isEqualTo(user.getId());
		assertThat(found.getTokenHash()).isEqualTo("token-hash-value");
		assertThat(found.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		assertThat(found.getConsumedAt()).isNull();
	}

	@Test
	void saveRejectsDuplicateTokenHashViaUniqueConstraint() {
		User user = saveUser("reauth-unique@finplay.com", "reauth-unique-user");
		reauthTokenRepository.saveAndFlush(
			ReauthToken.create(user, "duplicate-token-hash", NOW.plusMinutes(5), NOW));

		assertThatThrownBy(() -> reauthTokenRepository.saveAndFlush(
			ReauthToken.create(user, "duplicate-token-hash", NOW.plusMinutes(5), NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void saveRejectsNonExistentUserIdViaForeignKeyConstraint() {
		User transientUser = User.create("reauth-fk@finplay.com", "password-hash", "reauth-fk-user", NOW);
		ReflectionTestUtils.setField(transientUser, "id", 999_999L);
		ReauthToken orphanToken = ReauthToken.create(
			transientUser, "orphan-token-hash", NOW.plusMinutes(5), NOW);

		assertThatThrownBy(() -> reauthTokenRepository.saveAndFlush(orphanToken))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void consumeIfValidForUserSucceedsAndReturnsOneForOwnedUnexpiredUnconsumedToken() {
		User user = saveUser("reauth-consume@finplay.com", "reauth-consume-user");
		ReauthToken token = reauthTokenRepository.saveAndFlush(
			ReauthToken.create(user, "consume-token-hash", NOW.plusMinutes(5), NOW));

		assertThat(reauthTokenRepository.consumeIfValidForUser("consume-token-hash", user.getId(), NOW))
			.isEqualTo(1);

		entityManager.clear();
		ReauthToken consumed = reauthTokenRepository.findById(token.getId()).orElseThrow();
		assertThat(consumed.getConsumedAt()).isEqualTo(NOW);
	}

	@Test
	void consumeIfValidForUserReturnsZeroWhenAlreadyConsumed() {
		User user = saveUser("reauth-reuse@finplay.com", "reauth-reuse-user");
		ReauthToken token = reauthTokenRepository.saveAndFlush(
			ReauthToken.create(user, "reuse-token-hash", NOW.plusMinutes(5), NOW));

		assertThat(reauthTokenRepository.consumeIfValidForUser("reuse-token-hash", user.getId(), NOW))
			.isEqualTo(1);
		assertThat(
			reauthTokenRepository.consumeIfValidForUser("reuse-token-hash", user.getId(), NOW.plusSeconds(1)))
			.isZero();

		entityManager.clear();
		ReauthToken consumed = reauthTokenRepository.findById(token.getId()).orElseThrow();
		assertThat(consumed.getConsumedAt()).isEqualTo(NOW);
	}

	@Test
	void consumeIfValidForUserReturnsZeroWhenExpired() {
		User user = saveUser("reauth-expired@finplay.com", "reauth-expired-user");
		reauthTokenRepository.save(ReauthToken.create(user, "expires-now-token-hash", NOW, NOW.minusMinutes(5)));
		reauthTokenRepository.saveAndFlush(
			ReauthToken.create(user, "expired-token-hash", NOW.minusSeconds(1), NOW.minusMinutes(5)));

		assertThat(reauthTokenRepository.consumeIfValidForUser("expires-now-token-hash", user.getId(), NOW))
			.isZero();
		assertThat(reauthTokenRepository.consumeIfValidForUser("expired-token-hash", user.getId(), NOW))
			.isZero();
	}

	@Test
	void consumeIfValidForUserReturnsZeroWhenOwnedByDifferentUser() {
		User owner = saveUser("reauth-owner@finplay.com", "reauth-owner-user");
		User other = saveUser("reauth-other@finplay.com", "reauth-other-user");
		ReauthToken token = reauthTokenRepository.saveAndFlush(
			ReauthToken.create(owner, "owned-token-hash", NOW.plusMinutes(5), NOW));

		assertThat(reauthTokenRepository.consumeIfValidForUser("owned-token-hash", other.getId(), NOW)).isZero();

		entityManager.clear();
		ReauthToken untouched = reauthTokenRepository.findById(token.getId()).orElseThrow();
		assertThat(untouched.getConsumedAt()).isNull();
	}

	private User saveUser(String email, String nickname) {
		return userRepository.saveAndFlush(User.create(email, "password-hash", nickname, NOW));
	}
}
