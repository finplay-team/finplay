// 회원가입·로그인 서비스의 검증 분기와 저장 대상 상태를 검증하는 단위 테스트다.
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.crypto.Sha256BcryptPasswordEncoder;
import com.finplay.api.auth.domain.EmailVerification;
import com.finplay.api.auth.domain.RefreshToken;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.EmailVerificationRepository;
import com.finplay.api.auth.repository.RefreshTokenRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.IssuedTokenPair;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;

class AuthServiceTest {

	private static final String EMAIL = "user@finplay.com";
	private static final String NICKNAME = "finplayer";
	private static final String RAW_PASSWORD = "password123";
	private static final String SIGNUP_TOKEN = "signup-verification-token";
	private static final String ACCESS_TOKEN = "access.jwt.token";
	private static final String REFRESH_TOKEN = "refresh.jwt.token";
	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-25T10:30:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private UserRepository userRepository;
	private EmailVerificationRepository emailVerificationRepository;
	private RefreshTokenRepository refreshTokenRepository;
	private AccountService accountService;
	private JwtTokenProvider jwtTokenProvider;
	private PasswordEncoder passwordEncoder;
	private AuthService authService;

	@BeforeEach
	void setUp() {
		userRepository = mock(UserRepository.class);
		emailVerificationRepository = mock(EmailVerificationRepository.class);
		refreshTokenRepository = mock(RefreshTokenRepository.class);
		accountService = mock(AccountService.class);
		jwtTokenProvider = mock(JwtTokenProvider.class);
		passwordEncoder = new Sha256BcryptPasswordEncoder();
		Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		authService = new AuthService(userRepository, emailVerificationRepository, refreshTokenRepository,
			passwordEncoder, accountService, jwtTokenProvider, clock);
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

	private User existingUser(String passwordHash) {
		User user = User.create(EMAIL, passwordHash, NICKNAME, NOW.minusDays(1));
		ReflectionTestUtils.setField(user, "id", 7L);
		return user;
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
