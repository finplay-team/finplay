// reauth_tokens 저장과 token_hash UNIQUE·user_id FK 제약을 실제 MySQL로 검증하는 슬라이스 테스트다.
package com.finplay.api.auth.repository;

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
import com.finplay.api.auth.domain.ReauthToken;
import com.finplay.api.auth.domain.User;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class ReauthTokenRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 10, 30, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ReauthTokenRepository reauthTokenRepository;

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

	private User saveUser(String email, String nickname) {
		return userRepository.saveAndFlush(User.create(email, "password-hash", nickname, NOW));
	}
}
