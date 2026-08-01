// 회원가입·로그인·토큰 회전 서비스의 검증 분기와 저장 대상 상태를 검증하는 단위 테스트다.
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.crypto.Sha256BcryptPasswordEncoder;
import com.finplay.api.auth.domain.EmailVerification;
import com.finplay.api.auth.domain.ReauthToken;
import com.finplay.api.auth.domain.RefreshToken;
import com.finplay.api.auth.domain.SignupMethod;
import com.finplay.api.auth.domain.SocialAccount;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.dto.response.MemberResponse;
import com.finplay.api.auth.dto.response.ReauthTokenResponse;
import com.finplay.api.auth.exception.EmailChangeConflictException;
import com.finplay.api.auth.repository.EmailVerificationRepository;
import com.finplay.api.auth.repository.ReauthTokenRepository;
import com.finplay.api.auth.repository.RefreshTokenRepository;
import com.finplay.api.auth.repository.SocialAccountRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.oauth.OAuthNicknameGenerator;
import com.finplay.api.auth.oauth.OAuthProviderName;
import com.finplay.api.auth.oauth.OAuthUserDto;
import com.finplay.api.auth.oauth.ReauthTokenGenerator;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.IssuedTokenPair;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;

class AuthServiceTest {

	private static final String EMAIL = "user@finplay.com";
	private static final String NICKNAME = "finplayer";
	private static final String NEW_NICKNAME = "finplayer-renamed";
	private static final String NEW_EMAIL = "new@finplay.com";
	private static final String VERIFICATION_CODE = "123456";
	private static final String RAW_REAUTH_TOKEN = "raw-reauth-token";
	private static final String RAW_PASSWORD = "password123";
	private static final String NEW_PASSWORD = "new-password456";
	private static final String OAUTH_ONLY_PASSWORD_SENTINEL = "{oauth-only}";
	private static final String SIGNUP_TOKEN = "signup-verification-token";
	private static final String ACCESS_TOKEN = "access.jwt.token";
	private static final String REFRESH_TOKEN = "refresh.jwt.token";
	private static final String ROTATED_ACCESS_TOKEN = "rotated.access.jwt.token";
	private static final String ROTATED_REFRESH_TOKEN = "rotated.refresh.jwt.token";
	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-25T10:30:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private UserRepository userRepository;
	private EmailVerificationRepository emailVerificationRepository;
	private RefreshTokenRepository refreshTokenRepository;
	private SocialAccountRepository socialAccountRepository;
	private ReauthTokenRepository reauthTokenRepository;
	private EmailChangeService emailChangeService;
	private OAuthNicknameGenerator oauthNicknameGenerator;
	private ReauthTokenGenerator reauthTokenGenerator;
	private AccountService accountService;
	private JwtTokenProvider jwtTokenProvider;
	private PasswordEncoder passwordEncoder;
	private AuthService authService;

	@BeforeEach
	void setUp() {
		userRepository = mock(UserRepository.class);
		emailVerificationRepository = mock(EmailVerificationRepository.class);
		refreshTokenRepository = mock(RefreshTokenRepository.class);
		socialAccountRepository = mock(SocialAccountRepository.class);
		reauthTokenRepository = mock(ReauthTokenRepository.class);
		emailChangeService = mock(EmailChangeService.class);
		oauthNicknameGenerator = mock(OAuthNicknameGenerator.class);
		reauthTokenGenerator = mock(ReauthTokenGenerator.class);
		accountService = mock(AccountService.class);
		jwtTokenProvider = mock(JwtTokenProvider.class);
		passwordEncoder = new Sha256BcryptPasswordEncoder();
		Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		authService = new AuthService(
			userRepository,
			emailVerificationRepository,
			refreshTokenRepository,
			socialAccountRepository,
			reauthTokenRepository,
			emailChangeService,
			passwordEncoder,
			accountService,
			jwtTokenProvider,
			oauthNicknameGenerator,
			reauthTokenGenerator,
			clock);
	}

	@Test
	void signupRejectsDuplicateEmail() {
		stubValidVerification();
		when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

		assertSignupFailsWith(ErrorCode.DUPLICATE_RESOURCE);

		verify(emailVerificationRepository).findByTokenHash(sha256(SIGNUP_TOKEN));
		verify(emailVerificationRepository, never()).consumeValidToken(any(), any());
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void signupRejectsDuplicateNickname() {
		stubValidVerification();
		when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
		when(userRepository.existsByNickname(NICKNAME)).thenReturn(true);

		assertSignupFailsWith(ErrorCode.DUPLICATE_RESOURCE);

		verify(emailVerificationRepository).findByTokenHash(sha256(SIGNUP_TOKEN));
		verify(emailVerificationRepository, never()).consumeValidToken(any(), any());
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void signupRejectsUnknownToken() {
		stubNoDuplicates();
		when(emailVerificationRepository.findByTokenHash(sha256(SIGNUP_TOKEN))).thenReturn(Optional.empty());

		assertSignupFailsWith(ErrorCode.EMAIL_VERIFICATION_REQUIRED);

		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void signupRejectsExpiredOrConsumedToken() {
		stubNoDuplicates();
		EmailVerification expired = confirmedVerification(EMAIL, NOW);
		when(emailVerificationRepository.findByTokenHash(sha256(SIGNUP_TOKEN)))
			.thenReturn(Optional.of(expired));

		assertSignupFailsWith(ErrorCode.EMAIL_VERIFICATION_REQUIRED);

		EmailVerification consumed = confirmedVerification(EMAIL, NOW.plusMinutes(5));
		ReflectionTestUtils.setField(consumed, "consumedAt", NOW.minusSeconds(1));
		when(emailVerificationRepository.findByTokenHash(sha256(SIGNUP_TOKEN)))
			.thenReturn(Optional.of(consumed));

		assertSignupFailsWith(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void signupRejectsTokenEmailMismatch() {
		stubNoDuplicates();
		EmailVerification verification = confirmedVerification("other@finplay.com", NOW.plusMinutes(5));
		when(emailVerificationRepository.findByTokenHash(sha256(SIGNUP_TOKEN)))
			.thenReturn(Optional.of(verification));

		assertSignupFailsWith(ErrorCode.EMAIL_VERIFICATION_REQUIRED);

		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void signupRejectsWhenConditionalConsumeLosesRace() {
		stubNoDuplicates();
		stubValidVerification();
		when(emailVerificationRepository.consumeValidToken(sha256(SIGNUP_TOKEN), NOW)).thenReturn(0);

		assertSignupFailsWith(ErrorCode.EMAIL_VERIFICATION_REQUIRED);

		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void signupCreatesUserAccountsAndHashedRefreshToken() {
		stubNoDuplicates();
		stubValidVerification();
		when(emailVerificationRepository.consumeValidToken(sha256(SIGNUP_TOKEN), NOW)).thenReturn(1);
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
			User user = invocation.getArgument(0);
			ReflectionTestUtils.setField(user, "id", 7L);
			return user;
		});
		IssuedTokenPair issuedTokens = new IssuedTokenPair(
			ACCESS_TOKEN, REFRESH_TOKEN, NOW.plusDays(14), 3600L, 1_209_600L);
		when(jwtTokenProvider.issue(7L, "USER")).thenReturn(issuedTokens);

		var response = authService.signup(EMAIL, NICKNAME, RAW_PASSWORD, SIGNUP_TOKEN);

		ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
		verify(userRepository).saveAndFlush(userCaptor.capture());
		User savedUser = userCaptor.getValue();
		assertThat(savedUser.getEmail()).isEqualTo(EMAIL);
		assertThat(savedUser.getNickname()).isEqualTo(NICKNAME);
		assertThat(savedUser.getRole()).isEqualTo("USER");
		assertThat(savedUser.getStatus()).isEqualTo("ACTIVE");
		assertThat(savedUser.getCreatedAt()).isEqualTo(NOW);
		assertThat(savedUser.getUpdatedAt()).isEqualTo(NOW);
		assertThat(savedUser.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);
		assertThat(passwordEncoder.matches(RAW_PASSWORD, savedUser.getPasswordHash())).isTrue();

		verify(accountService).createAccountsFor(savedUser);

		ArgumentCaptor<RefreshToken> refreshTokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokenRepository).save(refreshTokenCaptor.capture());
		RefreshToken savedRefreshToken = refreshTokenCaptor.getValue();
		assertThat(savedRefreshToken.getUser()).isSameAs(savedUser);
		assertThat(savedRefreshToken.getTokenHash()).isEqualTo(sha256(REFRESH_TOKEN));
		assertThat(savedRefreshToken.getTokenHash()).isNotEqualTo(REFRESH_TOKEN);
		assertThat(savedRefreshToken.getExpiresAt()).isEqualTo(NOW.plusDays(14));
		assertThat(savedRefreshToken.getCreatedAt()).isEqualTo(NOW);
		assertThat(savedRefreshToken.getRevokedAt()).isNull();

		assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
		assertThat(response.refreshToken()).isEqualTo(REFRESH_TOKEN);
		assertThat(response.accessTokenExpiresInSeconds()).isEqualTo(3600L);
		assertThat(response.refreshTokenExpiresInSeconds()).isEqualTo(1_209_600L);
	}

	@Test
	void signupMapsConcurrentUserUniqueViolationToDuplicateResource() {
		stubNoDuplicates();
		stubValidVerification();
		when(emailVerificationRepository.consumeValidToken(sha256(SIGNUP_TOKEN), NOW)).thenReturn(1);
		when(userRepository.saveAndFlush(any(User.class)))
			.thenThrow(new DataIntegrityViolationException("concurrent duplicate"));

		assertSignupFailsWith(ErrorCode.DUPLICATE_RESOURCE);

		verify(accountService, never()).createAccountsFor(any());
		verify(refreshTokenRepository, never()).save(any());
	}

	@Test
	void loginReturnsTokenPairAndPersistsHashedRefreshToken() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		when(jwtTokenProvider.issue(7L, "USER")).thenReturn(new IssuedTokenPair(
			ACCESS_TOKEN, REFRESH_TOKEN, NOW.plusDays(14), 3600L, 1_209_600L));

		var response = authService.login(EMAIL, RAW_PASSWORD);

		ArgumentCaptor<RefreshToken> refreshTokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokenRepository).save(refreshTokenCaptor.capture());
		RefreshToken savedRefreshToken = refreshTokenCaptor.getValue();
		assertThat(savedRefreshToken.getUser()).isSameAs(user);
		assertThat(savedRefreshToken.getTokenHash()).isNotEqualTo(REFRESH_TOKEN);
		assertThat(savedRefreshToken.getTokenHash()).isEqualTo(sha256(REFRESH_TOKEN));
		assertThat(savedRefreshToken.getExpiresAt()).isEqualTo(NOW.plusDays(14));
		assertThat(savedRefreshToken.getCreatedAt()).isEqualTo(NOW);
		assertThat(savedRefreshToken.getRevokedAt()).isNull();

		assertThat(response.accessToken()).isEqualTo(ACCESS_TOKEN);
		assertThat(response.refreshToken()).isEqualTo(REFRESH_TOKEN);
		assertThat(response.accessTokenExpiresInSeconds()).isEqualTo(3600L);
		assertThat(response.refreshTokenExpiresInSeconds()).isEqualTo(1_209_600L);
	}

	@Test
	void loginFailsWithUnauthorizedWhenEmailNotFound() {
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

		assertLoginFailsWithUnauthorized(RAW_PASSWORD);
	}

	@Test
	void loginFailsWithUnauthorizedWhenPasswordDoesNotMatch() {
		when(userRepository.findByEmail(EMAIL))
			.thenReturn(Optional.of(existingUser(passwordEncoder.encode(RAW_PASSWORD))));

		assertLoginFailsWithUnauthorized("wrong-password");
	}

	@Test
	void loginFailsWithUnauthorizedWhenUserHasNoPasswordHash() {
		// 소셜 전용 가입자는 passwordHash가 null이다. 비밀번호 대조 전에 걸러져야 한다.
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existingUser(null)));

		assertLoginFailsWithUnauthorized(RAW_PASSWORD);
	}

	@Test
	void loginFailureIsIndistinguishableRegardlessOfCause() {
		// D7 — 원인별로 응답이 갈리면 이메일 존재 여부가 노출된다. 세 원인의 ErrorCode가 같아야 한다.
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
		ErrorCode emailNotFound = captureLoginErrorCode(RAW_PASSWORD);

		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existingUser(null)));
		ErrorCode noPasswordHash = captureLoginErrorCode(RAW_PASSWORD);

		when(userRepository.findByEmail(EMAIL))
			.thenReturn(Optional.of(existingUser(passwordEncoder.encode(RAW_PASSWORD))));
		ErrorCode passwordMismatch = captureLoginErrorCode("wrong-password");

		assertThat(List.of(emailNotFound, noPasswordHash, passwordMismatch))
			.containsOnly(ErrorCode.UNAUTHORIZED);
	}

	@Test
	void loginDoesNotRevokeExistingRefreshTokens() {
		// D9 — 로그인은 refresh_tokens에 행을 추가만 한다. 기존 토큰 삭제·폐기 호출이 있으면 안 된다.
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
		when(jwtTokenProvider.issue(7L, "USER")).thenReturn(new IssuedTokenPair(
			ACCESS_TOKEN, REFRESH_TOKEN, NOW.plusDays(14), 3600L, 1_209_600L));

		authService.login(EMAIL, RAW_PASSWORD);

		verify(refreshTokenRepository).save(any(RefreshToken.class));
		verifyNoMoreInteractions(refreshTokenRepository);
	}

	@Test
	void refreshReturnsRotatedPairAndPersistsOnlyNewRefreshTokenHash() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		RefreshToken storedToken = storedRefreshToken(user, 11L);
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(7L, "USER")));
		when(refreshTokenRepository.findAllByTokenHash(sha256(REFRESH_TOKEN)))
			.thenReturn(List.of(storedToken));
		when(refreshTokenRepository.revokeIfActiveAndNotExpired(11L, NOW)).thenReturn(1);
		when(jwtTokenProvider.issue(7L, "USER")).thenReturn(new IssuedTokenPair(
			ROTATED_ACCESS_TOKEN, ROTATED_REFRESH_TOKEN, NOW.plusDays(14), 3600L, 1_209_600L));

		var response = authService.refresh(REFRESH_TOKEN);

		verify(refreshTokenRepository).findAllByTokenHash(sha256(REFRESH_TOKEN));
		verify(refreshTokenRepository).revokeIfActiveAndNotExpired(11L, NOW);
		ArgumentCaptor<RefreshToken> refreshTokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokenRepository).save(refreshTokenCaptor.capture());
		RefreshToken savedRefreshToken = refreshTokenCaptor.getValue();
		assertThat(savedRefreshToken.getUser()).isSameAs(user);
		assertThat(savedRefreshToken.getTokenHash()).isEqualTo(sha256(ROTATED_REFRESH_TOKEN));
		assertThat(savedRefreshToken.getTokenHash()).isNotEqualTo(ROTATED_REFRESH_TOKEN);
		assertThat(savedRefreshToken.getExpiresAt()).isEqualTo(NOW.plusDays(14));
		assertThat(savedRefreshToken.getCreatedAt()).isEqualTo(NOW);
		assertThat(savedRefreshToken.getRevokedAt()).isNull();
		assertThat(response.accessToken()).isEqualTo(ROTATED_ACCESS_TOKEN);
		assertThat(response.refreshToken()).isEqualTo(ROTATED_REFRESH_TOKEN);
		assertThat(response.accessTokenExpiresInSeconds()).isEqualTo(3600L);
		assertThat(response.refreshTokenExpiresInSeconds()).isEqualTo(1_209_600L);
	}

	@Test
	void refreshFailsWithUnauthorizedWhenJwtParsingFails() {
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN)).thenReturn(Optional.empty());

		assertRefreshFailsWithUnauthorized();

		verifyNoInteractions(refreshTokenRepository);
		verifyRefreshDoesNotIssueOrSave();
	}

	@Test
	void refreshFailsWithUnauthorizedWhenHashDoesNotExist() {
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(7L, "USER")));
		when(refreshTokenRepository.findAllByTokenHash(sha256(REFRESH_TOKEN)))
			.thenReturn(List.of());

		assertRefreshFailsWithUnauthorized();

		verify(refreshTokenRepository, never()).revokeIfActiveAndNotExpired(any(), any());
		verifyRefreshDoesNotIssueOrSave();
	}

	@Test
	void refreshFailsWithUnauthorizedWhenHashMatchesMultipleRows() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		RefreshToken first = storedRefreshToken(user, 11L);
		RefreshToken second = storedRefreshToken(user, 12L);
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(7L, "USER")));
		when(refreshTokenRepository.findAllByTokenHash(sha256(REFRESH_TOKEN)))
			.thenReturn(List.of(first, second));

		assertRefreshFailsWithUnauthorized();

		verify(refreshTokenRepository, never()).revokeIfActiveAndNotExpired(any(), any());
		verifyRefreshDoesNotIssueOrSave();
	}

	@Test
	void refreshFailsWithUnauthorizedWhenJwtUserDoesNotMatchStoredUser() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		RefreshToken storedToken = storedRefreshToken(user, 11L);
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(8L, "USER")));
		when(refreshTokenRepository.findAllByTokenHash(sha256(REFRESH_TOKEN)))
			.thenReturn(List.of(storedToken));

		assertRefreshFailsWithUnauthorized();

		verify(refreshTokenRepository, never()).revokeIfActiveAndNotExpired(any(), any());
		verifyRefreshDoesNotIssueOrSave();
	}

	@Test
	void refreshFailsWithUnauthorizedWhenConditionalRevokeReturnsZero() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		RefreshToken storedToken = storedRefreshToken(user, 11L);
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(7L, "USER")));
		when(refreshTokenRepository.findAllByTokenHash(sha256(REFRESH_TOKEN)))
			.thenReturn(List.of(storedToken));
		when(refreshTokenRepository.revokeIfActiveAndNotExpired(11L, NOW)).thenReturn(0);

		assertRefreshFailsWithUnauthorized();

		verifyRefreshDoesNotIssueOrSave();
	}

	@Test
	void logoutRevokesSingleRefreshTokenOwnedByAuthenticatedUser() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		RefreshToken storedToken = storedRefreshToken(user, 11L);
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(7L, "USER")));
		when(refreshTokenRepository.findAllByTokenHash(sha256(REFRESH_TOKEN)))
			.thenReturn(List.of(storedToken));
		when(refreshTokenRepository.revokeIfActiveAndNotExpired(11L, NOW)).thenReturn(1);

		authService.logout(7L, REFRESH_TOKEN);

		verify(refreshTokenRepository).findAllByTokenHash(sha256(REFRESH_TOKEN));
		verify(refreshTokenRepository).revokeIfActiveAndNotExpired(11L, NOW);
		verifyLogoutDoesNotIssueOrSave();
	}

	@Test
	void logoutFailsWithUnauthorizedWhenJwtParsingFails() {
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN)).thenReturn(Optional.empty());

		assertLogoutFailsWith(ErrorCode.UNAUTHORIZED, 7L);

		verifyNoInteractions(refreshTokenRepository);
		verifyLogoutDoesNotIssueOrSave();
	}

	@Test
	void logoutFailsWithUnauthorizedWhenHashDoesNotExist() {
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(7L, "USER")));
		when(refreshTokenRepository.findAllByTokenHash(sha256(REFRESH_TOKEN)))
			.thenReturn(List.of());

		assertLogoutFailsWith(ErrorCode.UNAUTHORIZED, 7L);

		verify(refreshTokenRepository, never()).revokeIfActiveAndNotExpired(any(), any());
		verifyLogoutDoesNotIssueOrSave();
	}

	@Test
	void logoutFailsWithUnauthorizedWhenHashMatchesMultipleRows() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		RefreshToken first = storedRefreshToken(user, 11L);
		RefreshToken second = storedRefreshToken(user, 12L);
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(7L, "USER")));
		when(refreshTokenRepository.findAllByTokenHash(sha256(REFRESH_TOKEN)))
			.thenReturn(List.of(first, second));

		assertLogoutFailsWith(ErrorCode.UNAUTHORIZED, 7L);

		verify(refreshTokenRepository, never()).revokeIfActiveAndNotExpired(any(), any());
		verifyLogoutDoesNotIssueOrSave();
	}

	@Test
	void logoutFailsWithUnauthorizedWhenJwtUserDoesNotMatchStoredUser() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		RefreshToken storedToken = storedRefreshToken(user, 11L);
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(8L, "USER")));
		when(refreshTokenRepository.findAllByTokenHash(sha256(REFRESH_TOKEN)))
			.thenReturn(List.of(storedToken));

		assertLogoutFailsWith(ErrorCode.UNAUTHORIZED, 7L);

		verify(refreshTokenRepository, never()).revokeIfActiveAndNotExpired(any(), any());
		verifyLogoutDoesNotIssueOrSave();
	}

	@Test
	void logoutFailsWithForbiddenWhenAuthenticatedUserDoesNotOwnStoredToken() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		RefreshToken storedToken = storedRefreshToken(user, 11L);
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(7L, "USER")));
		when(refreshTokenRepository.findAllByTokenHash(sha256(REFRESH_TOKEN)))
			.thenReturn(List.of(storedToken));

		assertLogoutFailsWith(ErrorCode.FORBIDDEN, 8L);

		verify(refreshTokenRepository, never()).revokeIfActiveAndNotExpired(any(), any());
		verifyLogoutDoesNotIssueOrSave();
	}

	@Test
	void logoutFailsWithUnauthorizedWhenConditionalRevokeReturnsZero() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		RefreshToken storedToken = storedRefreshToken(user, 11L);
		when(jwtTokenProvider.parseRefreshToken(REFRESH_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(7L, "USER")));
		when(refreshTokenRepository.findAllByTokenHash(sha256(REFRESH_TOKEN)))
			.thenReturn(List.of(storedToken));
		when(refreshTokenRepository.revokeIfActiveAndNotExpired(11L, NOW)).thenReturn(0);

		assertLogoutFailsWith(ErrorCode.UNAUTHORIZED, 7L);

		verify(refreshTokenRepository).revokeIfActiveAndNotExpired(11L, NOW);
		verifyLogoutDoesNotIssueOrSave();
	}

	@Test
	void getMeReturnsMemberResponseWithSignupMethodEmailWhenNoSocialAccountExists() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(7L)).thenReturn(Optional.empty());

		MemberResponse response = authService.getMe(7L);

		assertThat(response.id()).isEqualTo(7L);
		assertThat(response.email()).isEqualTo(EMAIL);
		assertThat(response.nickname()).isEqualTo(NICKNAME);
		assertThat(response.signupMethod()).isEqualTo(SignupMethod.EMAIL);
	}

	@Test
	void getMeReturnsMemberResponseWithSignupMethodKakaoWhenKakaoSocialAccountExists() {
		assertThat(getMeSignupMethodFor(OAuthProviderName.KAKAO)).isEqualTo(SignupMethod.KAKAO);
	}

	@Test
	void getMeReturnsMemberResponseWithSignupMethodNaverWhenNaverSocialAccountExists() {
		assertThat(getMeSignupMethodFor(OAuthProviderName.NAVER)).isEqualTo(SignupMethod.NAVER);
	}

	@Test
	void getMeFailsWithUnauthorizedWhenUserNotFound() {
		when(userRepository.findById(7L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService.getMe(7L))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.UNAUTHORIZED);

		verifyNoInteractions(socialAccountRepository);
	}

	@Test
	void reauthenticateReturnsHashedTokenWithFiveMinuteTtlWhenSameMemberAndProviderMatch() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		OAuthUserDto oauthUser = new OAuthUserDto("provider-user-id", "user@kakao.example.com");
		SocialAccount socialAccount = SocialAccount.create(
			user, OAuthProviderName.KAKAO, "provider-user-id", NOW.minusDays(1));
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByProviderAndProviderUserId(
			OAuthProviderName.KAKAO, "provider-user-id")).thenReturn(Optional.of(socialAccount));
		when(reauthTokenGenerator.generate()).thenReturn("raw-reauth-token");

		ReauthTokenResponse response = authService.reauthenticate(7L, OAuthProviderName.KAKAO, oauthUser);

		assertThat(response.reauthToken()).isEqualTo("raw-reauth-token");
		assertThat(response.expiresInSeconds()).isEqualTo(300L);

		ArgumentCaptor<ReauthToken> reauthTokenCaptor = ArgumentCaptor.forClass(ReauthToken.class);
		verify(reauthTokenRepository).save(reauthTokenCaptor.capture());
		ReauthToken saved = reauthTokenCaptor.getValue();
		assertThat(saved.getUser()).isSameAs(user);
		assertThat(saved.getTokenHash()).isEqualTo(sha256("raw-reauth-token"));
		assertThat(saved.getTokenHash()).isNotEqualTo("raw-reauth-token");
		assertThat(saved.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
		assertThat(saved.getCreatedAt()).isEqualTo(NOW);

		verify(userRepository, never()).save(any());
		verify(socialAccountRepository, never()).save(any());
		verifyNoInteractions(accountService);
	}

	@Test
	void reauthenticateGeneratesDifferentRawTokensAcrossCalls() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		OAuthUserDto oauthUser = new OAuthUserDto("provider-user-id", "user@kakao.example.com");
		SocialAccount socialAccount = SocialAccount.create(
			user, OAuthProviderName.KAKAO, "provider-user-id", NOW.minusDays(1));
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByProviderAndProviderUserId(
			OAuthProviderName.KAKAO, "provider-user-id")).thenReturn(Optional.of(socialAccount));
		when(reauthTokenGenerator.generate()).thenReturn("raw-reauth-token-1", "raw-reauth-token-2");

		ReauthTokenResponse first = authService.reauthenticate(7L, OAuthProviderName.KAKAO, oauthUser);
		ReauthTokenResponse second = authService.reauthenticate(7L, OAuthProviderName.KAKAO, oauthUser);

		assertThat(first.reauthToken()).isNotEqualTo(second.reauthToken());

		ArgumentCaptor<ReauthToken> reauthTokenCaptor = ArgumentCaptor.forClass(ReauthToken.class);
		verify(reauthTokenRepository, times(2)).save(reauthTokenCaptor.capture());
		List<String> savedHashes = reauthTokenCaptor.getAllValues().stream()
			.map(ReauthToken::getTokenHash)
			.toList();
		assertThat(savedHashes).doesNotHaveDuplicates();
		assertThat(savedHashes)
			.noneMatch(hash -> hash.equals("raw-reauth-token-1") || hash.equals("raw-reauth-token-2"));
	}

	@Test
	void reauthenticateFailsWithReauthenticationFailedWhenUserIdDoesNotExist() {
		OAuthUserDto oauthUser = new OAuthUserDto("provider-user-id", "user@kakao.example.com");
		when(userRepository.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService.reauthenticate(999L, OAuthProviderName.KAKAO, oauthUser))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);

		verifyNoInteractions(socialAccountRepository);
		verifyNoInteractions(reauthTokenRepository);
		verifyNoInteractions(reauthTokenGenerator);
		verifyNoInteractions(accountService);
	}

	@Test
	void reauthenticateFailsWithReauthenticationFailedWhenProviderNotLinkedToAnyMember() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		OAuthUserDto oauthUser = new OAuthUserDto("unlinked-provider-user-id", "user@kakao.example.com");
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByProviderAndProviderUserId(
			OAuthProviderName.KAKAO, "unlinked-provider-user-id")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService.reauthenticate(7L, OAuthProviderName.KAKAO, oauthUser))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);

		verifyNoInteractions(reauthTokenRepository);
		verifyNoInteractions(reauthTokenGenerator);
		verifyNoInteractions(accountService);
		verify(userRepository, never()).save(any());
	}

	@Test
	void reauthenticateFailsWithReauthenticationFailedWhenSocialAccountBelongsToAnotherMember() {
		User authenticatedUser = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		User anotherMember = User.create("other@finplay.com", passwordEncoder.encode(RAW_PASSWORD), "other-nick",
			NOW.minusDays(2));
		ReflectionTestUtils.setField(anotherMember, "id", 42L);
		OAuthUserDto oauthUser = new OAuthUserDto("provider-user-id", "other@kakao.example.com");
		SocialAccount socialAccountOfAnotherMember = SocialAccount.create(
			anotherMember, OAuthProviderName.KAKAO, "provider-user-id", NOW.minusDays(1));
		when(userRepository.findById(7L)).thenReturn(Optional.of(authenticatedUser));
		when(socialAccountRepository.findByProviderAndProviderUserId(
			OAuthProviderName.KAKAO, "provider-user-id")).thenReturn(Optional.of(socialAccountOfAnotherMember));

		assertThatThrownBy(() -> authService.reauthenticate(7L, OAuthProviderName.KAKAO, oauthUser))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);

		verifyNoInteractions(reauthTokenRepository);
		verifyNoInteractions(reauthTokenGenerator);
		verifyNoInteractions(accountService);
		verify(userRepository, never()).save(any());
		verify(socialAccountRepository, never()).save(any());
	}

	@Test
	void changeNicknameSucceedsForEmailUserWithCorrectPassword() {
		User user = stubEmailUser();
		when(userRepository.existsByNicknameAndIdNot(NEW_NICKNAME, 7L)).thenReturn(false);
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		MemberResponse response = authService.changeNickname(7L, NEW_NICKNAME, RAW_PASSWORD, null);

		assertThat(response.id()).isEqualTo(7L);
		assertThat(response.email()).isEqualTo(EMAIL);
		assertThat(response.nickname()).isEqualTo(NEW_NICKNAME);
		assertThat(response.signupMethod()).isEqualTo(SignupMethod.EMAIL);

		ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
		verify(userRepository).saveAndFlush(userCaptor.capture());
		User savedUser = userCaptor.getValue();
		assertThat(savedUser).isSameAs(user);
		assertThat(savedUser.getNickname()).isEqualTo(NEW_NICKNAME);
		assertThat(savedUser.getEmail()).isEqualTo(EMAIL);
		assertThat(savedUser.getUpdatedAt()).isEqualTo(NOW);

		verifyNoInteractions(reauthTokenRepository);
		verifyNoInteractions(accountService);
		verifyNoInteractions(refreshTokenRepository);
		verify(socialAccountRepository, never()).save(any());
	}

	@Test
	void changeNicknameFailsWithReauthenticationFailedForEmailUserWithWrongPassword() {
		stubEmailUser();

		assertChangeNicknameFailsWith(
			ErrorCode.REAUTHENTICATION_FAILED, NEW_NICKNAME, "wrong-password", null);

		verify(userRepository, never()).saveAndFlush(any());
		verifyNoInteractions(reauthTokenRepository);
	}

	@Test
	void changeNicknameFailsWithValidationErrorWhenEmailUserOmitsCurrentPassword() {
		stubEmailUser();

		assertChangeNicknameFailsWith(ErrorCode.VALIDATION_ERROR, NEW_NICKNAME, "  ", null);

		verify(userRepository, never()).saveAndFlush(any());
		verifyNoInteractions(reauthTokenRepository);
	}

	@Test
	void changeNicknameSucceedsForOAuthUserWithValidReauthToken() {
		User user = stubOAuthUser(OAuthProviderName.KAKAO);
		when(reauthTokenRepository.consumeIfValidForUser(sha256(RAW_REAUTH_TOKEN), 7L, NOW)).thenReturn(1);
		when(userRepository.existsByNicknameAndIdNot(NEW_NICKNAME, 7L)).thenReturn(false);
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		MemberResponse response = authService.changeNickname(7L, NEW_NICKNAME, null, RAW_REAUTH_TOKEN);

		assertThat(response.nickname()).isEqualTo(NEW_NICKNAME);
		assertThat(response.signupMethod()).isEqualTo(SignupMethod.KAKAO);
		assertThat(user.getNickname()).isEqualTo(NEW_NICKNAME);

		// 원문이 아니라 해시로 소비돼야 한다.
		verify(reauthTokenRepository).consumeIfValidForUser(sha256(RAW_REAUTH_TOKEN), 7L, NOW);
		verify(reauthTokenRepository, never()).consumeIfValidForUser(RAW_REAUTH_TOKEN, 7L, NOW);
		verify(userRepository).saveAndFlush(user);
		verifyNoInteractions(accountService);
		verifyNoInteractions(refreshTokenRepository);
		verify(socialAccountRepository, never()).save(any());
	}

	@Test
	void changeNicknameFailsWithReauthenticationFailedForOAuthUserWhenConsumeReturnsZero() {
		stubOAuthUser(OAuthProviderName.NAVER);
		when(reauthTokenRepository.consumeIfValidForUser(sha256(RAW_REAUTH_TOKEN), 7L, NOW)).thenReturn(0);

		assertChangeNicknameFailsWith(
			ErrorCode.REAUTHENTICATION_FAILED, NEW_NICKNAME, null, RAW_REAUTH_TOKEN);

		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void changeNicknameFailsWithValidationErrorWhenOAuthUserOmitsReauthToken() {
		stubOAuthUser(OAuthProviderName.KAKAO);

		assertChangeNicknameFailsWith(ErrorCode.VALIDATION_ERROR, NEW_NICKNAME, RAW_PASSWORD, "  ");

		verify(userRepository, never()).saveAndFlush(any());
		verifyNoInteractions(reauthTokenRepository);
	}

	@Test
	void changeNicknameFailsWithDuplicateResourceWhenNicknameOwnedByAnotherUser() {
		stubEmailUser();
		when(userRepository.existsByNicknameAndIdNot(NEW_NICKNAME, 7L)).thenReturn(true);

		assertChangeNicknameFailsWith(ErrorCode.DUPLICATE_RESOURCE, NEW_NICKNAME, RAW_PASSWORD, null);

		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void changeNicknameFailsWithDuplicateResourceOnConcurrentUniqueViolation() {
		stubEmailUser();
		when(userRepository.existsByNicknameAndIdNot(NEW_NICKNAME, 7L)).thenReturn(false);
		when(userRepository.saveAndFlush(any(User.class)))
			.thenThrow(new DataIntegrityViolationException("concurrent duplicate"));

		assertChangeNicknameFailsWith(ErrorCode.DUPLICATE_RESOURCE, NEW_NICKNAME, RAW_PASSWORD, null);
	}

	@Test
	void changeNicknameSucceedsAsNoOpWhenNewNicknameEqualsCurrentNickname() {
		User user = stubEmailUser();
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		MemberResponse response = authService.changeNickname(7L, NICKNAME, RAW_PASSWORD, null);

		assertThat(response.nickname()).isEqualTo(NICKNAME);
		assertThat(user.getUpdatedAt()).isEqualTo(NOW);
		verify(userRepository, never()).existsByNicknameAndIdNot(any(), any());
		verify(userRepository).saveAndFlush(user);
	}

	@Test
	void changeNicknameFailsWithUnauthorizedWhenUserNotFound() {
		when(userRepository.findById(7L)).thenReturn(Optional.empty());

		assertChangeNicknameFailsWith(ErrorCode.UNAUTHORIZED, NEW_NICKNAME, RAW_PASSWORD, null);

		verifyNoInteractions(socialAccountRepository);
		verifyNoInteractions(reauthTokenRepository);
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void confirmEmailChangeSucceedsInOrderAndReturnsUpdatedMemberResponse() {
		User user = stubEmailUser();
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

		MemberResponse response = authService.confirmEmailChange(7L, NEW_EMAIL, VERIFICATION_CODE);

		assertThat(response.id()).isEqualTo(7L);
		assertThat(response.email()).isEqualTo(NEW_EMAIL);
		assertThat(response.nickname()).isEqualTo(NICKNAME);
		assertThat(response.signupMethod()).isEqualTo(SignupMethod.EMAIL);
		assertThat(user.getEmail()).isEqualTo(NEW_EMAIL);
		assertThat(user.getUpdatedAt()).isEqualTo(NOW);

		InOrder inOrder = inOrder(emailChangeService, userRepository, refreshTokenRepository);
		inOrder.verify(emailChangeService).validateAndConsumeCode(7L, NEW_EMAIL, VERIFICATION_CODE);
		inOrder.verify(userRepository).saveAndFlush(user);
		inOrder.verify(refreshTokenRepository).revokeAllActiveByUserId(7L, NOW);
	}

	@Test
	void confirmEmailChangeStopsBeforeEmailChangeWhenValidationThrowsBusinessException() {
		stubEmailUser();
		doThrow(new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED))
			.when(emailChangeService)
			.validateAndConsumeCode(7L, NEW_EMAIL, VERIFICATION_CODE);

		assertThatThrownBy(() -> authService.confirmEmailChange(7L, NEW_EMAIL, VERIFICATION_CODE))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);

		verify(userRepository, never()).saveAndFlush(any());
		verifyNoInteractions(refreshTokenRepository);
	}

	@Test
	void confirmEmailChangeConvertsUniqueViolationToConflictAndSkipsTokenRevocation() {
		stubEmailUser();
		when(userRepository.saveAndFlush(any(User.class)))
			.thenThrow(new DataIntegrityViolationException("concurrent duplicate email"));

		assertThatThrownBy(() -> authService.confirmEmailChange(7L, NEW_EMAIL, VERIFICATION_CODE))
			.isInstanceOf(EmailChangeConflictException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.DUPLICATE_RESOURCE);

		verify(emailChangeService).validateAndConsumeCode(7L, NEW_EMAIL, VERIFICATION_CODE);
		verifyNoInteractions(refreshTokenRepository);
	}

	@Test
	void changePasswordSucceedsAndReissuesTokenPairForEmailUser() {
		User user = stubEmailUser();
		String originalHash = user.getPasswordHash();
		stubSaveAndFlushReturningArgument();
		stubIssuedRotatedTokenPair();

		var response = authService.changePassword(7L, RAW_PASSWORD, NEW_PASSWORD);

		// 저장되는 값은 원문이 아니라 새 비밀번호로 대조되는 해시여야 한다.
		assertThat(user.getPasswordHash()).isNotEqualTo(originalHash);
		assertThat(user.getPasswordHash()).isNotEqualTo(NEW_PASSWORD);
		assertThat(passwordEncoder.matches(NEW_PASSWORD, user.getPasswordHash())).isTrue();
		assertThat(passwordEncoder.matches(RAW_PASSWORD, user.getPasswordHash())).isFalse();
		assertThat(user.getUpdatedAt()).isEqualTo(NOW);
		assertThat(user.getEmail()).isEqualTo(EMAIL);
		assertThat(user.getNickname()).isEqualTo(NICKNAME);

		verify(userRepository).saveAndFlush(user);
		verify(refreshTokenRepository).revokeAllActiveByUserId(7L, NOW);

		ArgumentCaptor<RefreshToken> refreshTokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
		verify(refreshTokenRepository).save(refreshTokenCaptor.capture());
		RefreshToken savedRefreshToken = refreshTokenCaptor.getValue();
		assertThat(savedRefreshToken.getUser()).isSameAs(user);
		assertThat(savedRefreshToken.getTokenHash()).isEqualTo(sha256(ROTATED_REFRESH_TOKEN));
		assertThat(savedRefreshToken.getTokenHash()).isNotEqualTo(ROTATED_REFRESH_TOKEN);
		assertThat(savedRefreshToken.getExpiresAt()).isEqualTo(NOW.plusDays(14));
		assertThat(savedRefreshToken.getCreatedAt()).isEqualTo(NOW);
		assertThat(savedRefreshToken.getRevokedAt()).isNull();

		assertThat(response.accessToken()).isEqualTo(ROTATED_ACCESS_TOKEN);
		assertThat(response.refreshToken()).isEqualTo(ROTATED_REFRESH_TOKEN);
		assertThat(response.accessTokenExpiresInSeconds()).isEqualTo(3600L);
		assertThat(response.refreshTokenExpiresInSeconds()).isEqualTo(1_209_600L);

		verifyNoInteractions(accountService, reauthTokenRepository, emailChangeService);
	}

	@Test
	void changePasswordRevokesAllRefreshTokensBeforeIssuingNewPair() {
		// D5 — RefreshToken은 IDENTITY라 save가 즉시 INSERT된다.
		// 발급이 먼저면 revokedAt IS NULL 조건에 방금 만든 행까지 걸려 요청 기기도 로그아웃된다.
		stubEmailUser();
		stubSaveAndFlushReturningArgument();
		stubIssuedRotatedTokenPair();

		authService.changePassword(7L, RAW_PASSWORD, NEW_PASSWORD);

		InOrder inOrder = inOrder(refreshTokenRepository);
		inOrder.verify(refreshTokenRepository).revokeAllActiveByUserId(7L, NOW);
		inOrder.verify(refreshTokenRepository).save(any(RefreshToken.class));
		inOrder.verifyNoMoreInteractions();
	}

	@Test
	void changePasswordPersistsNewHashBeforeRevokingRefreshTokens() {
		stubEmailUser();
		stubSaveAndFlushReturningArgument();
		stubIssuedRotatedTokenPair();

		authService.changePassword(7L, RAW_PASSWORD, NEW_PASSWORD);

		InOrder inOrder = inOrder(userRepository, refreshTokenRepository);
		inOrder.verify(userRepository).saveAndFlush(any(User.class));
		inOrder.verify(refreshTokenRepository).revokeAllActiveByUserId(7L, NOW);
	}

	@Test
	void changePasswordFailsWithReauthenticationFailedWhenCurrentPasswordMismatches() {
		User user = stubEmailUser();
		String originalHash = user.getPasswordHash();

		assertThat(captureChangePasswordFailure("wrong-password", NEW_PASSWORD).getErrorCode())
			.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);

		assertThat(user.getPasswordHash()).isEqualTo(originalHash);
		verifyChangePasswordChangedNothing();
	}

	@Test
	void changePasswordFailsWithReauthenticationFailedWhenWrongCurrentPasswordEqualsNewPassword() {
		// D3 — 동일 여부 검사가 대조보다 먼저면 현재 비밀번호를 모르는 요청자도 400 분기를 관찰할 수 있다.
		User user = stubEmailUser();
		String originalHash = user.getPasswordHash();

		assertThat(captureChangePasswordFailure("wrong-password", "wrong-password").getErrorCode())
			.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);

		assertThat(user.getPasswordHash()).isEqualTo(originalHash);
		verifyChangePasswordChangedNothing();
	}

	@Test
	void changePasswordFailsWithValidationErrorWhenNewPasswordEqualsCurrentPassword() {
		User user = stubEmailUser();
		String originalHash = user.getPasswordHash();

		BusinessException exception = captureChangePasswordFailure(RAW_PASSWORD, RAW_PASSWORD);

		assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
		assertThat(exception.getMessage()).isEqualTo("새 비밀번호는 현재 비밀번호와 달라야 합니다.");
		assertThat(user.getPasswordHash()).isEqualTo(originalHash);
		verifyChangePasswordChangedNothing();
	}

	@Test
	void changePasswordFailsWithValidationErrorWhenEmailUserOmitsCurrentPassword() {
		User user = stubEmailUser();
		String originalHash = user.getPasswordHash();

		assertThat(captureChangePasswordFailure("  ", NEW_PASSWORD).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);

		assertThat(user.getPasswordHash()).isEqualTo(originalHash);
		verifyChangePasswordChangedNothing();
	}

	@Test
	void changePasswordFailsWithValidationErrorForOAuthOnlyUserBeforeComparingPassword() {
		// D2·D3 — sentinel 해시는 어떤 원문과도 일치하지 않는다. 대조가 먼저였다면 403이 나온다.
		User user = stubOAuthOnlyUser();

		BusinessException exception = captureChangePasswordFailure(RAW_PASSWORD, NEW_PASSWORD);

		assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
		assertThat(exception.getMessage()).isEqualTo("OAuth 전용 회원은 비밀번호를 변경할 수 없습니다.");
		assertThat(user.getPasswordHash()).isEqualTo(OAUTH_ONLY_PASSWORD_SENTINEL);
		assertThat(user.getUpdatedAt()).isEqualTo(NOW.minusDays(1));
		verifyChangePasswordChangedNothing();
		verify(socialAccountRepository, never()).save(any());
	}

	@Test
	void changePasswordFailsWithUnauthorizedWhenUserNotFound() {
		when(userRepository.findById(7L)).thenReturn(Optional.empty());

		assertThat(captureChangePasswordFailure(RAW_PASSWORD, NEW_PASSWORD).getErrorCode())
			.isEqualTo(ErrorCode.UNAUTHORIZED);

		verifyNoInteractions(socialAccountRepository);
		verifyChangePasswordChangedNothing();
	}

	@Test
	void signupMethodFromProviderMapsEachOAuthProvider() {
		assertThat(SignupMethod.fromProvider(OAuthProviderName.KAKAO)).isEqualTo(SignupMethod.KAKAO);
		assertThat(SignupMethod.fromProvider(OAuthProviderName.NAVER)).isEqualTo(SignupMethod.NAVER);
	}

	private SignupMethod getMeSignupMethodFor(OAuthProviderName provider) {
		User user = existingUser(null);
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(7L)).thenReturn(
			Optional.of(SocialAccount.create(user, provider, "provider-user-id", NOW.minusDays(1))));

		return authService.getMe(7L).signupMethod();
	}

	private User stubEmailUser() {
		User user = existingUser(passwordEncoder.encode(RAW_PASSWORD));
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(7L)).thenReturn(Optional.empty());
		return user;
	}

	private User stubOAuthUser(OAuthProviderName provider) {
		User user = existingUser(null);
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(7L)).thenReturn(
			Optional.of(SocialAccount.create(user, provider, "provider-user-id", NOW.minusDays(1))));
		return user;
	}

	private User stubOAuthOnlyUser() {
		User user = existingUser(OAUTH_ONLY_PASSWORD_SENTINEL);
		when(userRepository.findById(7L)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(7L)).thenReturn(
			Optional.of(SocialAccount.create(
				user, OAuthProviderName.KAKAO, "provider-user-id", NOW.minusDays(1))));
		return user;
	}

	private void stubSaveAndFlushReturningArgument() {
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private void stubIssuedRotatedTokenPair() {
		when(jwtTokenProvider.issue(7L, "USER")).thenReturn(new IssuedTokenPair(
			ROTATED_ACCESS_TOKEN, ROTATED_REFRESH_TOKEN, NOW.plusDays(14), 3600L, 1_209_600L));
	}

	private BusinessException captureChangePasswordFailure(String currentPassword, String newPassword) {
		try {
			authService.changePassword(7L, currentPassword, newPassword);
			throw new AssertionError("changePassword가 BusinessException을 던지지 않았다.");
		} catch (BusinessException ex) {
			return ex;
		}
	}

	private void verifyChangePasswordChangedNothing() {
		verify(userRepository, never()).saveAndFlush(any());
		verify(refreshTokenRepository, never()).revokeAllActiveByUserId(any(), any());
		verify(refreshTokenRepository, never()).save(any());
		verify(jwtTokenProvider, never()).issue(any(), any());
	}

	private void assertChangeNicknameFailsWith(
		ErrorCode errorCode, String newNickname, String currentPassword, String reauthToken) {
		assertThatThrownBy(
			() -> authService.changeNickname(7L, newNickname, currentPassword, reauthToken))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(errorCode);
	}

	private User existingUser(String passwordHash) {
		User user = User.create(EMAIL, passwordHash, NICKNAME, NOW.minusDays(1));
		ReflectionTestUtils.setField(user, "id", 7L);
		return user;
	}

	private RefreshToken storedRefreshToken(User user, Long id) {
		RefreshToken refreshToken = RefreshToken.create(
			user, sha256(REFRESH_TOKEN), NOW.plusDays(14), NOW.minusDays(1));
		ReflectionTestUtils.setField(refreshToken, "id", id);
		return refreshToken;
	}

	private void assertRefreshFailsWithUnauthorized() {
		assertThatThrownBy(() -> authService.refresh(REFRESH_TOKEN))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.UNAUTHORIZED);
	}

	private void verifyRefreshDoesNotIssueOrSave() {
		verify(jwtTokenProvider, never()).issue(any(), any());
		verify(refreshTokenRepository, never()).save(any());
	}

	private void assertLogoutFailsWith(ErrorCode errorCode, Long authenticatedUserId) {
		assertThatThrownBy(() -> authService.logout(authenticatedUserId, REFRESH_TOKEN))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(errorCode);
	}

	private void verifyLogoutDoesNotIssueOrSave() {
		verify(jwtTokenProvider, never()).issue(any(), any());
		verify(refreshTokenRepository, never()).save(any());
	}

	private void assertLoginFailsWithUnauthorized(String password) {
		assertThat(captureLoginErrorCode(password)).isEqualTo(ErrorCode.UNAUTHORIZED);

		// 실패 경로에서는 토큰이 발급되거나 저장되지 않아야 한다.
		verifyNoInteractions(jwtTokenProvider);
		verify(refreshTokenRepository, never()).save(any());
	}

	private ErrorCode captureLoginErrorCode(String password) {
		try {
			authService.login(EMAIL, password);
			throw new AssertionError("login이 BusinessException을 던지지 않았다.");
		} catch (BusinessException ex) {
			return ex.getErrorCode();
		}
	}

	private void stubNoDuplicates() {
		when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
		when(userRepository.existsByNickname(NICKNAME)).thenReturn(false);
	}

	private void stubValidVerification() {
		when(emailVerificationRepository.findByTokenHash(sha256(SIGNUP_TOKEN)))
			.thenReturn(Optional.of(confirmedVerification(EMAIL, NOW.plusMinutes(5))));
	}

	private EmailVerification confirmedVerification(String email, LocalDateTime tokenExpiresAt) {
		EmailVerification verification = EmailVerification.create(
			email, "code-hash", NOW.minusMinutes(1), NOW.minusMinutes(10));
		verification.confirm(NOW.minusMinutes(1), sha256(SIGNUP_TOKEN), tokenExpiresAt);
		return verification;
	}

	private void assertSignupFailsWith(ErrorCode errorCode) {
		assertThatThrownBy(() -> authService.signup(EMAIL, NICKNAME, RAW_PASSWORD, SIGNUP_TOKEN))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(errorCode);
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
