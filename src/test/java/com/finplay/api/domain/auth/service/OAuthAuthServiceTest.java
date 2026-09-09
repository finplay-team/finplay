package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.crypto.Sha256BcryptPasswordEncoder;
import com.finplay.api.domain.auth.entity.SocialAccount;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.oauth.OAuthNicknameGenerator;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
import com.finplay.api.domain.auth.oauth.exchange.ReauthTokenGenerator;
import com.finplay.api.domain.auth.repository.EmailVerificationRepository;
import com.finplay.api.domain.auth.repository.ReauthTokenRepository;
import com.finplay.api.domain.auth.repository.RefreshTokenRepository;
import com.finplay.api.domain.auth.repository.SocialAccountRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.IssuedTokenPair;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

class OAuthAuthServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 26, 10, 0);
	private static final OAuthUserDto OAUTH_USER = new OAuthUserDto("provider-user-id", "member@example.com");

	private UserRepository users;
	private RefreshTokenRepository refreshTokens;
	private SocialAccountRepository socialAccounts;
	private ReauthTokenRepository reauthTokens;
	private AccountService accounts;
	private JwtTokenProvider tokens;
	private OAuthNicknameGenerator nicknames;
	private ReauthTokenGenerator reauthTokenGenerator;
	private AuthService service;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		refreshTokens = mock(RefreshTokenRepository.class);
		socialAccounts = mock(SocialAccountRepository.class);
		reauthTokens = mock(ReauthTokenRepository.class);
		accounts = mock(AccountService.class);
		tokens = mock(JwtTokenProvider.class);
		nicknames = mock(OAuthNicknameGenerator.class);
		reauthTokenGenerator = mock(ReauthTokenGenerator.class);
		service = new AuthService(
			users,
			mock(EmailVerificationRepository.class),
			refreshTokens,
			socialAccounts,
			reauthTokens,
			mock(EmailChangeService.class),
			mock(PasswordResetService.class),
			new Sha256BcryptPasswordEncoder(),
			accounts,
			tokens,
			nicknames,
			reauthTokenGenerator,
			Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
	}

	@ParameterizedTest
	@MethodSource("invalidUsers")
	void invalidProviderOrUserFailsBeforePersistence(
		OAuthProviderName provider, OAuthUserDto oauthUser, ErrorCode errorCode) {
		assertThatThrownBy(() -> service.oauthLogin(provider, oauthUser))
			.isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(errorCode));

		verifyNoInteractions(users, socialAccounts, accounts, tokens, refreshTokens, nicknames);
	}

	@Test
	void existingSocialLoginIgnoresChangedEmailAndOnlyIssuesAndPersistsTokens() {
		User existing = user("old@example.com", "existing");
		SocialAccount social = SocialAccount.create(
			existing, OAuthProviderName.KAKAO, "provider-user-id", NOW.minusDays(1));
		given(socialAccounts.findByProviderAndProviderUserId(
			OAuthProviderName.KAKAO, "provider-user-id")).willReturn(Optional.of(social));
		given(tokens.issue(7L, "USER")).willReturn(issuedTokens());

		service.oauthLogin(
			OAuthProviderName.KAKAO,
			new OAuthUserDto("provider-user-id", "changed@example.com"));

		verify(tokens).issue(7L, "USER");
		verify(refreshTokens).save(any());
		verify(users, never()).existsByEmail(any());
		verify(users, never()).saveAndFlush(any());
		verify(socialAccounts, never()).saveAndFlush(any());
		verifyNoInteractions(accounts, nicknames);
	}

	@Test
	void newSocialLoginRejectsExistingEmailBeforeSaving() {
		given(socialAccounts.findByProviderAndProviderUserId(
			OAuthProviderName.KAKAO, OAUTH_USER.providerUserId())).willReturn(Optional.empty());
		given(users.existsByEmail(OAUTH_USER.email())).willReturn(true);

		assertOAuthFailure(ErrorCode.ACCOUNT_LINK_REQUIRED);

		verify(users, never()).saveAndFlush(any());
		verify(socialAccounts, never()).saveAndFlush(any());
		verifyNoInteractions(accounts, tokens, refreshTokens, nicknames);
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4})
	void nicknameCollisionsFromZeroThroughFourEventuallySucceed(int collisions) {
		given(socialAccounts.findByProviderAndProviderUserId(
			OAuthProviderName.KAKAO, OAUTH_USER.providerUserId())).willReturn(Optional.empty());
		given(users.existsByEmail(OAUTH_USER.email())).willReturn(false);
		AtomicInteger sequence = new AtomicInteger();
		given(nicknames.generate()).willAnswer(invocation -> "finplay-%012x".formatted(sequence.getAndIncrement()));
		given(users.existsByNickname(anyString())).willAnswer(invocation -> {
			String nickname = invocation.getArgument(0);
			int suffix = Integer.parseInt(nickname.substring("finplay-".length()), 16);
			return suffix < collisions;
		});
		given(users.saveAndFlush(any())).willAnswer(invocation -> {
			User saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 7L);
			return saved;
		});
		given(tokens.issue(7L, "USER")).willReturn(issuedTokens());

		service.oauthLogin(OAuthProviderName.KAKAO, OAUTH_USER);

		verify(nicknames, times(collisions + 1)).generate();
		verify(socialAccounts).saveAndFlush(any());
		verify(accounts).createAccountsFor(any());
		verify(refreshTokens).save(any());
	}

	@Test
	void fiveNicknameCollisionsFailWithInternalErrorBeforeSaving() {
		given(socialAccounts.findByProviderAndProviderUserId(
			OAuthProviderName.KAKAO, OAUTH_USER.providerUserId())).willReturn(Optional.empty());
		given(users.existsByEmail(OAUTH_USER.email())).willReturn(false);
		given(nicknames.generate()).willReturn(
			"finplay-000000000000",
			"finplay-000000000001",
			"finplay-000000000002",
			"finplay-000000000003",
			"finplay-000000000004");
		IntStream.range(0, 5).forEach(index -> given(
			users.existsByNickname("finplay-00000000000" + index)).willReturn(true));

		assertOAuthFailure(ErrorCode.INTERNAL_ERROR);

		verify(nicknames, times(5)).generate();
		verify(users, never()).saveAndFlush(any());
		verify(socialAccounts, never()).saveAndFlush(any());
		verifyNoInteractions(accounts, tokens, refreshTokens);
	}

	@Test
	void oauthOnlyPasswordAlwaysFailsRegularLoginAsUnauthorized() {
		User oauthOnly = user("oauth@example.com", "oauth-only");
		given(users.findByEmail("oauth@example.com")).willReturn(Optional.of(oauthOnly));
		String guessedRawPassword = oauthOnly.getPasswordHash();

		assertThatThrownBy(() -> service.login("oauth@example.com", guessedRawPassword))
			.isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));

		verifyNoInteractions(tokens, refreshTokens);
	}

	private void assertOAuthFailure(ErrorCode errorCode) {
		assertThatThrownBy(() -> service.oauthLogin(OAuthProviderName.KAKAO, OAUTH_USER))
			.isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(errorCode));
	}

	private static Stream<Arguments> invalidUsers() {
		return Stream.of(
			Arguments.of(null, OAUTH_USER, ErrorCode.OAUTH_PROVIDER_ERROR),
			Arguments.of(OAuthProviderName.KAKAO, null, ErrorCode.OAUTH_PROVIDER_ERROR),
			Arguments.of(
				OAuthProviderName.KAKAO,
				new OAuthUserDto(null, "member@example.com"),
				ErrorCode.OAUTH_PROVIDER_ERROR),
			Arguments.of(
				OAuthProviderName.KAKAO,
				new OAuthUserDto(" ", "member@example.com"),
				ErrorCode.OAUTH_PROVIDER_ERROR),
			Arguments.of(
				OAuthProviderName.KAKAO,
				new OAuthUserDto("provider-user-id", null),
				ErrorCode.OAUTH_EMAIL_REQUIRED),
			Arguments.of(
				OAuthProviderName.KAKAO,
				new OAuthUserDto("provider-user-id", " "),
				ErrorCode.OAUTH_EMAIL_REQUIRED));
	}

	private static User user(String email, String nickname) {
		User user = User.createOAuthOnly(email, nickname, NOW.minusDays(1));
		ReflectionTestUtils.setField(user, "id", 7L);
		return user;
	}

	private static IssuedTokenPair issuedTokens() {
		return new IssuedTokenPair(
			"access-token",
			"refresh-token",
			NOW.plusDays(14),
			3600L,
			1_209_600L);
	}
}
