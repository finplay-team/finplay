// reauth_tokens 저장과 token_hash UNIQUE·user_id FK 제약을 실제 MySQL로 검증하는 슬라이스 테스트다.
package com.finplay.api.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.ReauthToken;
import com.finplay.api.auth.domain.User;

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
	@DisplayName("유효한 토큰을 소비하면 영향받은 행이 1이고 consumedAt이 반영된다")
	void consumeIfValidForUserConsumesValidTokenAndReturnsOne() {
		User user = saveUser("reauth-consume@finplay.com", "reauth-consume-user");
		ReauthToken token = reauthTokenRepository.saveAndFlush(
			ReauthToken.create(user, "consume-valid-hash", NOW.plusMinutes(5), NOW));

		int updated = reauthTokenRepository
			.consumeIfValidForUser("consume-valid-hash", user.getId(), NOW.plusMinutes(1));

		assertThat(updated).isEqualTo(1);
		entityManager.clear();
		ReauthToken found = reauthTokenRepository.findById(token.getId()).orElseThrow();
		assertThat(found.getConsumedAt()).isEqualTo(NOW.plusMinutes(1));
	}

	@Test
	@DisplayName("이미 소비된 토큰은 재소비 시 영향받은 행이 0이고 최초 소비 시각이 유지된다")
	void consumeIfValidForUserFailsWhenAlreadyConsumed() {
		User user = saveUser("reauth-already@finplay.com", "reauth-already-user");
		ReauthToken token = reauthTokenRepository.saveAndFlush(
			ReauthToken.create(user, "already-consumed-hash", NOW.plusMinutes(5), NOW));
		int firstConsume = reauthTokenRepository
			.consumeIfValidForUser("already-consumed-hash", user.getId(), NOW.plusMinutes(1));
		assertThat(firstConsume).isEqualTo(1);

		int secondConsume = reauthTokenRepository
			.consumeIfValidForUser("already-consumed-hash", user.getId(), NOW.plusMinutes(2));

		assertThat(secondConsume).isEqualTo(0);
		entityManager.clear();
		ReauthToken found = reauthTokenRepository.findById(token.getId()).orElseThrow();
		assertThat(found.getConsumedAt()).isEqualTo(NOW.plusMinutes(1));
	}

	@Test
	@DisplayName("만료된 토큰은 소비 시 영향받은 행이 0이고 consumedAt이 그대로 NULL이다")
	void consumeIfValidForUserFailsWhenExpired() {
		User user = saveUser("reauth-expired@finplay.com", "reauth-expired-user");
		ReauthToken token = reauthTokenRepository.saveAndFlush(
			ReauthToken.create(user, "expired-hash", NOW.plusMinutes(5), NOW));

		int updated = reauthTokenRepository
			.consumeIfValidForUser("expired-hash", user.getId(), NOW.plusMinutes(10));

		assertThat(updated).isEqualTo(0);
		ReauthToken found = reauthTokenRepository.findById(token.getId()).orElseThrow();
		assertThat(found.getConsumedAt()).isNull();
	}

	@Test
	@DisplayName("다른 userId로 소비를 시도하면 영향받은 행이 0이고 원래 소유자 토큰은 그대로 유효하다")
	void consumeIfValidForUserFailsWhenUserIdDoesNotMatch() {
		User owner = saveUser("reauth-owner@finplay.com", "reauth-owner-user");
		User stranger = saveUser("reauth-stranger@finplay.com", "reauth-stranger-user");
		ReauthToken token = reauthTokenRepository.saveAndFlush(
			ReauthToken.create(owner, "owner-only-hash", NOW.plusMinutes(5), NOW));

		int updated = reauthTokenRepository
			.consumeIfValidForUser("owner-only-hash", stranger.getId(), NOW.plusMinutes(1));

		assertThat(updated).isEqualTo(0);
		ReauthToken found = reauthTokenRepository.findById(token.getId()).orElseThrow();
		assertThat(found.getConsumedAt()).isNull();
	}

	@Test
	@DisplayName("존재하지 않는 토큰 해시로 소비를 시도하면 영향받은 행이 0이다")
	void consumeIfValidForUserFailsWhenTokenHashDoesNotExist() {
		User user = saveUser("reauth-missing@finplay.com", "reauth-missing-user");

		int updated = reauthTokenRepository
			.consumeIfValidForUser("no-such-hash", user.getId(), NOW.plusMinutes(1));

		assertThat(updated).isEqualTo(0);
	}

	private User saveUser(String email, String nickname) {
		return userRepository.saveAndFlush(User.create(email, "password-hash", nickname, NOW));
	}
}
