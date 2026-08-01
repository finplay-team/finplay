// User의 이메일·비밀번호 변경 메서드를 검증하는 순수 단위 테스트다.
package com.finplay.api.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class UserTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 28, 10, 30, 0);

	@Test
	void changeEmailUpdatesEmailAndUpdatedAt() {
		User user = User.create("old@finplay.com", "password-hash", "user-nickname", NOW);

		user.changeEmail("new@finplay.com", NOW.plusMinutes(1));

		assertThat(user.getEmail()).isEqualTo("new@finplay.com");
		assertThat(user.getUpdatedAt()).isEqualTo(NOW.plusMinutes(1));
	}

	@Test
	void changePasswordUpdatesPasswordHashAndUpdatedAt() {
		User user = User.create("user@finplay.com", "old-password-hash", "user-nickname", NOW);

		user.changePassword("new-password-hash", NOW.plusMinutes(1));

		assertThat(user.getPasswordHash()).isEqualTo("new-password-hash");
		assertThat(user.getUpdatedAt()).isEqualTo(NOW.plusMinutes(1));
	}

	@Test
	void changePasswordKeepsEveryOtherFieldUnchanged() {
		User user = User.create("user@finplay.com", "old-password-hash", "user-nickname", NOW);

		user.changePassword("new-password-hash", NOW.plusMinutes(1));

		assertThat(user.getEmail()).isEqualTo("user@finplay.com");
		assertThat(user.getNickname()).isEqualTo("user-nickname");
		assertThat(user.getRole()).isEqualTo("USER");
		assertThat(user.getStatus()).isEqualTo("ACTIVE");
		assertThat(user.getCreatedAt()).isEqualTo(NOW);
	}
}
