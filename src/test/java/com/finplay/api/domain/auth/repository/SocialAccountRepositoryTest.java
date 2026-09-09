package com.finplay.api.domain.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.SocialAccount;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class SocialAccountRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 26, 10, 0);

	@Autowired
	private UserRepository users;

	@Autowired
	private SocialAccountRepository socialAccounts;

	@Test
	void savesAndFindsByProviderAndProviderUserId() {
		User user = saveUser("lookup");
		socialAccounts.saveAndFlush(
			SocialAccount.create(user, OAuthProviderName.KAKAO, "provider-id", NOW));

		SocialAccount found = socialAccounts
			.findByProviderAndProviderUserId(OAuthProviderName.KAKAO, "provider-id")
			.orElseThrow();

		assertThat(found.getUser().getId()).isEqualTo(user.getId());
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void sameProviderAndProviderUserIdViolatesUniqueConstraint() {
		User first = saveUser("unique-a");
		User second = saveUser("unique-b");
		socialAccounts.saveAndFlush(
			SocialAccount.create(first, OAuthProviderName.NAVER, "same-id", NOW));

		assertThatThrownBy(() -> socialAccounts.saveAndFlush(
			SocialAccount.create(second, OAuthProviderName.NAVER, "same-id", NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void differentProvidersMayUseSameProviderUserId() {
		User first = saveUser("provider-a");
		User second = saveUser("provider-b");
		socialAccounts.saveAndFlush(
			SocialAccount.create(first, OAuthProviderName.KAKAO, "same-id", NOW));
		socialAccounts.saveAndFlush(
			SocialAccount.create(second, OAuthProviderName.NAVER, "same-id", NOW));

		assertThat(socialAccounts.findByProviderAndProviderUserId(
			OAuthProviderName.KAKAO, "same-id")).isPresent();
		assertThat(socialAccounts.findByProviderAndProviderUserId(
			OAuthProviderName.NAVER, "same-id")).isPresent();
	}

	@Test
	void missingUserViolatesForeignKey() {
		User transientUser = User.create(
			"transient@example.com", "hash", "transient-user", NOW);

		assertThatThrownBy(() -> socialAccounts.saveAndFlush(
			SocialAccount.create(
				transientUser, OAuthProviderName.KAKAO, "orphan-id", NOW)))
			.isInstanceOf(RuntimeException.class);
	}

	private User saveUser(String suffix) {
		return users.saveAndFlush(User.create(
			suffix + "@example.com", "hash", suffix, NOW));
	}
}
