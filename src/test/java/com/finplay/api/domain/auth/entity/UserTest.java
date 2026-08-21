// User의 이메일·비밀번호 변경 메서드를 검증하는 순수 단위 테스트다.
package com.finplay.api.domain.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
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
	void hasPasswordIsTrueOnlyForRealPasswordHash() {
		User emailUser = User.create("email@finplay.com", "encoded-password-hash", "email-user", NOW);

		assertThat(emailUser.hasPassword()).isTrue();
	}

	@Test
	void hasPasswordIsFalseForOAuthOnlySentinelBecauseThereIsNoPasswordToReset() {
		// OAuth 가입자는 password_hash가 NULL이 아니라 자리표시자다 — NULL 검사만으로는 걸러지지 않는다.
		User oauthUser = User.createOAuthOnly("oauth@finplay.com", "oauth-user", NOW);

		assertThat(oauthUser.getPasswordHash()).isNotNull();
		assertThat(oauthUser.hasPassword()).isFalse();
	}

	@Test
	@DisplayName("createOAuthOnly는 비밀번호 없는 회원을 만들고 이메일·닉네임·시각을 채운다")
	void createOAuthOnlyBuildsUserWithoutPasswordAndFillsRemainingFields() {
		User oauthUser = User.createOAuthOnly("oauth-factory@finplay.com", "oauth-factory-user", NOW);

		assertThat(oauthUser.hasPassword()).isFalse();
		assertThat(oauthUser.getEmail()).isEqualTo("oauth-factory@finplay.com");
		assertThat(oauthUser.getNickname()).isEqualTo("oauth-factory-user");
		assertThat(oauthUser.getCreatedAt()).isEqualTo(NOW);
		assertThat(oauthUser.getUpdatedAt()).isEqualTo(NOW);
		assertThat(oauthUser.getRole()).isEqualTo("USER");
		assertThat(oauthUser.getStatus()).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("createOAuthOnly가 채우는 자리표시자는 NULL이 아니어서 NULL 검사만으로는 걸러지지 않는다")
	void createOAuthOnlyFillsNonNullPlaceholderSoNullCheckAloneCannotDetectIt() {
		User oauthUser = User.createOAuthOnly("oauth-placeholder@finplay.com", "oauth-placeholder-user", NOW);
		User emailUser = User.create("email@finplay.com", "encoded-password-hash", "email-user", NOW);

		// 두 회원 모두 password_hash가 NULL이 아니다 — 구분은 hasPassword()로만 된다.
		assertThat(oauthUser.getPasswordHash()).isNotNull();
		assertThat(emailUser.getPasswordHash()).isNotNull();
		assertThat(oauthUser.hasPassword()).isFalse();
		assertThat(emailUser.hasPassword()).isTrue();
		// 자리표시자는 어떤 실제 해시와도 같지 않다.
		assertThat(oauthUser.getPasswordHash()).isNotEqualTo(emailUser.getPasswordHash());
	}

	@Test
	void hasPasswordIsFalseWhenPasswordHashIsNull() {
		User user = User.create("null-hash@finplay.com", null, "null-hash-user", NOW);

		assertThat(user.hasPassword()).isFalse();
	}

	@Test
	void changePasswordMakesOAuthOnlyUserHavePassword() {
		User oauthUser = User.createOAuthOnly("oauth-link@finplay.com", "oauth-link-user", NOW);

		oauthUser.changePassword("encoded-password-hash", NOW.plusMinutes(1));

		assertThat(oauthUser.hasPassword()).isTrue();
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
