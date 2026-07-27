// Fake 사용자 정보로 OAuth 신규·기존 로그인과 실패·롤백을 실제 MySQL에서 통합 검증한다.
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.RefreshToken;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.oauth.OAuthProviderName;
import com.finplay.api.auth.oauth.OAuthUserDto;
import com.finplay.api.auth.repository.RefreshTokenRepository;
import com.finplay.api.auth.repository.SocialAccountRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OAuthLoginIntegrationTest {

	@Autowired
	private AuthService authService;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private Clock clock;

	@MockitoSpyBean
	private UserRepository users;

	@MockitoSpyBean
	private SocialAccountRepository socialAccounts;

	@MockitoSpyBean
	private AccountRepository accounts;

	@MockitoSpyBean
	private RefreshTokenRepository refreshTokens;

	@BeforeEach
	void clearSpyStubs() {
		reset(users, socialAccounts, accounts, refreshTokens);
	}

	@Test
	void newOAuthLoginPersistsWholeAggregateAndUsableJwt() {
		String suffix = uniqueSuffix();
		String email = suffix + "@example.com";
		String providerUserId = "provider-" + suffix;

		TokenResponse response = authService.oauthLogin(
			OAuthProviderName.KAKAO, new OAuthUserDto(providerUserId, email));

		User user = users.findByEmail(email).orElseThrow();
		assertThat(user.getNickname()).matches("^finplay-[0-9a-f]{12}$");
		assertThat(user.getNickname()).doesNotContain(email, providerUserId);
		assertThat(socialAccounts.findByProviderAndProviderUserId(
			OAuthProviderName.KAKAO, providerUserId)).isPresent();
		assertThat(accounts.findAllByUserId(user.getId()))
			.hasSize(2)
			.extracting(Account::getMarket)
			.containsExactlyInAnyOrder(Market.STOCK, Market.CRYPTO);
		assertThat(accounts.findAllByUserId(user.getId())).allSatisfy(account -> {
			assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
			assertThat(account.getSeedMoney()).isEqualTo(10_000_000L);
		});
		assertThat(refreshTokens.findAllByTokenHash(sha256(response.refreshToken()))).hasSize(1);
		assertThat(refreshTokens.findAll())
			.extracting(RefreshToken::getTokenHash)
			.doesNotContain(response.refreshToken());
		assertThat(jwtTokenProvider.parseAccessToken(response.accessToken()))
			.contains(new AuthenticatedUser(user.getId(), "USER"));
	}

	@Test
	void sameProviderUserLogsIntoExistingUserWithoutDuplicateAggregate() {
		String suffix = uniqueSuffix();
		String providerUserId = "provider-" + suffix;
		String email = suffix + "@example.com";
		authService.oauthLogin(
			OAuthProviderName.NAVER, new OAuthUserDto(providerUserId, email));
		User user = users.findByEmail(email).orElseThrow();
		long usersBefore = users.count();
		long socialsBefore = socialAccounts.count();
		long accountsBefore = accounts.count();
		long refreshBefore = refreshTokens.count();

		TokenResponse response = authService.oauthLogin(
			OAuthProviderName.NAVER,
			new OAuthUserDto(providerUserId, "changed-" + email));

		assertThat(response.accessToken()).isNotBlank();
		assertThat(users.count()).isEqualTo(usersBefore);
		assertThat(socialAccounts.count()).isEqualTo(socialsBefore);
		assertThat(accounts.count()).isEqualTo(accountsBefore);
		assertThat(refreshTokens.count()).isEqualTo(refreshBefore + 1);
		assertThat(accounts.findAllByUserId(user.getId())).hasSize(2);
	}

	@Test
	void missingEmailAndExistingRegularEmailLeaveNoOAuthRows() {
		String suffix = uniqueSuffix();
		long usersBefore = users.count();
		long socialsBefore = socialAccounts.count();
		long accountsBefore = accounts.count();
		long refreshBefore = refreshTokens.count();

		assertThatThrownBy(() -> authService.oauthLogin(
			OAuthProviderName.KAKAO, new OAuthUserDto("missing-" + suffix, null)))
			.isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.OAUTH_EMAIL_REQUIRED));
		assertCounts(usersBefore, socialsBefore, accountsBefore, refreshBefore);

		LocalDateTime now = LocalDateTime.now(clock);
		String existingEmail = "regular-" + suffix + "@example.com";
		users.saveAndFlush(User.create(existingEmail, "password-hash", "regular-" + suffix, now));
		long usersAfterRegular = users.count();

		assertThatThrownBy(() -> authService.oauthLogin(
			OAuthProviderName.NAVER,
			new OAuthUserDto("collision-" + suffix, existingEmail)))
			.isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ACCOUNT_LINK_REQUIRED));
		assertCounts(usersAfterRegular, socialsBefore, accountsBefore, refreshBefore);
	}

	@Test
	void socialAccountSaveRuntimeExceptionRollsBackUser() {
		String suffix = uniqueSuffix();
		Counts before = counts();
		doThrow(new IllegalStateException("forced social save failure"))
			.when(socialAccounts).saveAndFlush(any());

		assertRollback(
			() -> authService.oauthLogin(
				OAuthProviderName.KAKAO,
				new OAuthUserDto("social-" + suffix, suffix + "@example.com")),
			"forced social save failure",
			before);
	}

	@Test
	void accountSaveRuntimeExceptionRollsBackUserAndSocialAccount() {
		String suffix = uniqueSuffix();
		Counts before = counts();
		doThrow(new IllegalStateException("forced account save failure"))
			.when(accounts).saveAll(any());

		assertRollback(
			() -> authService.oauthLogin(
				OAuthProviderName.KAKAO,
				new OAuthUserDto("account-" + suffix, suffix + "@example.com")),
			"forced account save failure",
			before);
	}

	@Test
	void refreshSaveRuntimeExceptionRollsBackWholeOAuthAggregate() {
		String suffix = uniqueSuffix();
		Counts before = counts();
		doThrow(new IllegalStateException("forced refresh save failure"))
			.when(refreshTokens).save(any());

		assertRollback(
			() -> authService.oauthLogin(
				OAuthProviderName.NAVER,
				new OAuthUserDto("refresh-" + suffix, suffix + "@example.com")),
			"forced refresh save failure",
			before);
	}

	private void assertRollback(
		org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
		String message,
		Counts before) {
		assertThatThrownBy(invocation)
			.isInstanceOf(IllegalStateException.class)
			.hasMessage(message);
		assertThat(counts()).isEqualTo(before);
	}

	private void assertCounts(long userCount, long socialCount, long accountCount, long refreshCount) {
		assertThat(users.count()).isEqualTo(userCount);
		assertThat(socialAccounts.count()).isEqualTo(socialCount);
		assertThat(accounts.count()).isEqualTo(accountCount);
		assertThat(refreshTokens.count()).isEqualTo(refreshCount);
	}

	private Counts counts() {
		return new Counts(
			users.count(), socialAccounts.count(), accounts.count(), refreshTokens.count());
	}

	private static String uniqueSuffix() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private record Counts(long users, long socials, long accounts, long refreshTokens) {
	}
}
