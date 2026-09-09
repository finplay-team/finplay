package com.finplay.api.domain.auth.service;

import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.dto.response.MemberResponse;
import com.finplay.api.domain.auth.dto.response.ReauthTokenResponse;
import com.finplay.api.domain.auth.dto.response.TokenResponse;
import com.finplay.api.domain.auth.entity.EmailVerification;
import com.finplay.api.domain.auth.entity.ReauthToken;
import com.finplay.api.domain.auth.entity.RefreshToken;
import com.finplay.api.domain.auth.entity.SignupMethod;
import com.finplay.api.domain.auth.entity.SocialAccount;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.exception.EmailChangeConflictException;
import com.finplay.api.domain.auth.oauth.OAuthNicknameGenerator;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
import com.finplay.api.domain.auth.oauth.exchange.ReauthTokenGenerator;
import com.finplay.api.domain.auth.repository.EmailVerificationRepository;
import com.finplay.api.domain.auth.repository.ReauthTokenRepository;
import com.finplay.api.domain.auth.repository.RefreshTokenRepository;
import com.finplay.api.domain.auth.repository.SocialAccountRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.IssuedTokenPair;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private static final int MAX_NICKNAME_ATTEMPTS = 5;
	private static final Duration REAUTH_TOKEN_TTL = Duration.ofMinutes(5);

	private final UserRepository userRepository;
	private final EmailVerificationRepository emailVerificationRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final SocialAccountRepository socialAccountRepository;
	private final ReauthTokenRepository reauthTokenRepository;
	private final EmailChangeService emailChangeService;
	private final PasswordResetService passwordResetService;
	private final PasswordEncoder passwordEncoder;
	private final AccountService accountService;
	private final JwtTokenProvider jwtTokenProvider;
	private final OAuthNicknameGenerator oauthNicknameGenerator;
	private final ReauthTokenGenerator reauthTokenGenerator;
	private final Clock clock;

	public AuthService(
		UserRepository userRepository,
		EmailVerificationRepository emailVerificationRepository,
		RefreshTokenRepository refreshTokenRepository,
		SocialAccountRepository socialAccountRepository,
		ReauthTokenRepository reauthTokenRepository,
		EmailChangeService emailChangeService,
		PasswordResetService passwordResetService,
		PasswordEncoder passwordEncoder,
		AccountService accountService,
		JwtTokenProvider jwtTokenProvider,
		OAuthNicknameGenerator oauthNicknameGenerator,
		ReauthTokenGenerator reauthTokenGenerator,
		Clock clock) {
		this.userRepository = userRepository;
		this.emailVerificationRepository = emailVerificationRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.socialAccountRepository = socialAccountRepository;
		this.reauthTokenRepository = reauthTokenRepository;
		this.emailChangeService = emailChangeService;
		this.passwordResetService = passwordResetService;
		this.passwordEncoder = passwordEncoder;
		this.accountService = accountService;
		this.jwtTokenProvider = jwtTokenProvider;
		this.oauthNicknameGenerator = oauthNicknameGenerator;
		this.reauthTokenGenerator = reauthTokenGenerator;
		this.clock = clock;
	}

	@Transactional
	public TokenResponse signup(
		String email, String nickname, String password, String signupVerificationToken) {
		LocalDateTime now = LocalDateTime.now(clock);
		String tokenHash = sha256(signupVerificationToken);
		findAndValidateVerification(tokenHash, email, now);
		checkDuplicate(email, nickname);

		int consumed = emailVerificationRepository.consumeValidToken(tokenHash, now);
		if (consumed != 1) {
			throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
		}

		User user = saveUser(email, passwordEncoder.encode(password), nickname, now);
		accountService.createAccountsFor(user);

		return issueTokenPair(user, now);
	}

	@Transactional
	public TokenResponse login(String email, String password) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.findByEmail(email)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		if (!user.hasPassword() || !passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return issueTokenPair(user, now);
	}

	@Transactional
	public TokenResponse oauthLogin(OAuthProviderName provider, OAuthUserDto oauthUser) {
		validateOAuthUser(provider, oauthUser);
		LocalDateTime now = LocalDateTime.now(clock);

		return socialAccountRepository.findByProviderAndProviderUserId(
			provider, oauthUser.providerUserId())
			.map(socialAccount -> issueTokenPair(socialAccount.getUser(), now))
			.orElseGet(() -> createOAuthUser(provider, oauthUser, now));
	}

	@Transactional
	public ReauthTokenResponse reauthenticate(
		Long userId, OAuthProviderName provider, OAuthUserDto oauthUser) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.REAUTHENTICATION_FAILED));
		SocialAccount socialAccount = socialAccountRepository.findByProviderAndProviderUserId(
			provider, oauthUser.providerUserId())
			.orElseThrow(() -> new BusinessException(ErrorCode.REAUTHENTICATION_FAILED));
		if (!socialAccount.getUser().getId().equals(userId)) {
			throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
		}

		LocalDateTime now = LocalDateTime.now(clock);
		String rawToken = reauthTokenGenerator.generate();
		reauthTokenRepository.save(
			ReauthToken.create(user, sha256(rawToken), now.plus(REAUTH_TOKEN_TTL), now));

		return new ReauthTokenResponse(rawToken, REAUTH_TOKEN_TTL.toSeconds());
	}

	@Transactional(readOnly = true)
	public MemberResponse getMe(Long userId) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		SignupMethod signupMethod = socialAccountRepository.findByUserId(userId)
			.map(socialAccount -> SignupMethod.fromProvider(socialAccount.getProvider()))
			.orElse(SignupMethod.EMAIL);
		return MemberResponse.from(user, signupMethod);
	}

	@Transactional
	public MemberResponse changeNickname(
		Long userId, String newNickname, String currentPassword, String reauthToken) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		SignupMethod signupMethod = socialAccountRepository.findByUserId(userId)
			.map(socialAccount -> SignupMethod.fromProvider(socialAccount.getProvider()))
			.orElse(SignupMethod.EMAIL);

		if (signupMethod == SignupMethod.EMAIL) {
			verifyCurrentPassword(user, currentPassword);
		} else {
			consumeReauthToken(userId, reauthToken, now);
		}

		if (!user.getNickname().equals(newNickname)
			&& userRepository.existsByNicknameAndIdNot(newNickname, userId)) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}
		user.changeNickname(newNickname, now);
		try {
			userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException ex) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}

		return MemberResponse.from(user, signupMethod);
	}

	@Transactional(noRollbackFor = BusinessException.class, rollbackFor = EmailChangeConflictException.class)
	public MemberResponse confirmEmailChange(Long userId, String newEmail, String code) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		LocalDateTime now = LocalDateTime.now(clock);

		emailChangeService.validateAndConsumeCode(userId, newEmail, code);

		user.changeEmail(newEmail, now);
		try {
			userRepository.saveAndFlush(user);
		} catch (DataIntegrityViolationException ex) {
			throw new EmailChangeConflictException();
		}
		refreshTokenRepository.revokeAllActiveByUserId(userId, now);

		SignupMethod signupMethod = socialAccountRepository.findByUserId(userId)
			.map(socialAccount -> SignupMethod.fromProvider(socialAccount.getProvider()))
			.orElse(SignupMethod.EMAIL);
		return MemberResponse.from(user, signupMethod);
	}

	@Transactional
	public TokenResponse changePassword(Long userId, String currentPassword, String newPassword) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

		SignupMethod signupMethod = socialAccountRepository.findByUserId(userId)
			.map(socialAccount -> SignupMethod.fromProvider(socialAccount.getProvider()))
			.orElse(SignupMethod.EMAIL);
		if (signupMethod != SignupMethod.EMAIL) {
			throw new BusinessException(
				ErrorCode.VALIDATION_ERROR, "OAuth 전용 회원은 비밀번호를 변경할 수 없습니다.");
		}

		verifyCurrentPassword(user, currentPassword);

		if (currentPassword.equals(newPassword)) {
			throw new BusinessException(
				ErrorCode.VALIDATION_ERROR, "새 비밀번호는 현재 비밀번호와 달라야 합니다.");
		}

		user.changePassword(passwordEncoder.encode(newPassword), now);
		userRepository.saveAndFlush(user);

		refreshTokenRepository.revokeAllActiveByUserId(userId, now);
		return issueTokenPair(user, now);
	}

	@Transactional(noRollbackFor = BusinessException.class)
	public void confirmPasswordReset(String email, String code, String newPassword) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = passwordResetService.validateAndConsumeCode(email, code);

		user.changePassword(passwordEncoder.encode(newPassword), now);
		userRepository.saveAndFlush(user);

		refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
	}

	@Transactional
	public TokenResponse refresh(String rawRefreshToken) {
		AuthenticatedUser authenticatedUser = jwtTokenProvider.parseRefreshToken(rawRefreshToken)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		LocalDateTime now = LocalDateTime.now(clock);
		var refreshTokens = refreshTokenRepository.findAllByTokenHash(sha256(rawRefreshToken));
		if (refreshTokens.size() != 1) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}

		RefreshToken refreshToken = refreshTokens.get(0);
		User user = refreshToken.getUser();
		if (!authenticatedUser.userId().equals(user.getId())) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}

		int revoked = refreshTokenRepository.revokeIfActiveAndNotExpired(refreshToken.getId(), now);
		if (revoked != 1) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return issueTokenPair(user, now);
	}

	@Transactional
	public void logout(Long authenticatedUserId, String rawRefreshToken) {
		AuthenticatedUser refreshTokenUser = jwtTokenProvider.parseRefreshToken(rawRefreshToken)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		LocalDateTime now = LocalDateTime.now(clock);
		var refreshTokens = refreshTokenRepository.findAllByTokenHash(sha256(rawRefreshToken));
		if (refreshTokens.size() != 1) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}

		RefreshToken refreshToken = refreshTokens.get(0);
		Long refreshTokenOwnerId = refreshToken.getUser().getId();
		if (!refreshTokenUser.userId().equals(refreshTokenOwnerId)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		if (!authenticatedUserId.equals(refreshTokenOwnerId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}

		int revoked = refreshTokenRepository.revokeIfActiveAndNotExpired(refreshToken.getId(), now);
		if (revoked != 1) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
	}

	private void verifyCurrentPassword(User user, String currentPassword) {
		if (currentPassword == null || currentPassword.isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "이메일 회원은 현재 비밀번호가 필요합니다.");
		}
		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
		}
	}

	private void consumeReauthToken(Long userId, String reauthToken, LocalDateTime now) {
		if (reauthToken == null || reauthToken.isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "OAuth 회원은 재인증 토큰이 필요합니다.");
		}
		int consumed = reauthTokenRepository.consumeIfValidForUser(sha256(reauthToken), userId, now);
		if (consumed != 1) {
			throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
		}
	}

	private TokenResponse issueTokenPair(User user, LocalDateTime now) {
		IssuedTokenPair tokens = jwtTokenProvider.issue(user.getId(), user.getRole());
		refreshTokenRepository.save(RefreshToken.create(
			user, sha256(tokens.refreshToken()), tokens.refreshTokenExpiresAt(), now));
		return TokenResponse.from(tokens);
	}

	private TokenResponse createOAuthUser(
		OAuthProviderName provider, OAuthUserDto oauthUser, LocalDateTime now) {
		if (userRepository.existsByEmail(oauthUser.email())) {
			throw new BusinessException(ErrorCode.ACCOUNT_LINK_REQUIRED);
		}

		String nickname = generateAvailableOAuthNickname();
		User user = saveOAuthUser(oauthUser.email(), nickname, now);
		saveSocialAccount(user, provider, oauthUser.providerUserId(), now);
		accountService.createAccountsFor(user);

		return issueTokenPair(user, now);
	}

	private void validateOAuthUser(OAuthProviderName provider, OAuthUserDto oauthUser) {
		if (provider == null
			|| oauthUser == null
			|| oauthUser.providerUserId() == null
			|| oauthUser.providerUserId().isBlank()) {
			throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
		}
		if (oauthUser.email() == null || oauthUser.email().isBlank()) {
			throw new BusinessException(ErrorCode.OAUTH_EMAIL_REQUIRED);
		}
	}

	private String generateAvailableOAuthNickname() {
		for (int attempt = 0; attempt < MAX_NICKNAME_ATTEMPTS; attempt++) {
			String nickname = oauthNicknameGenerator.generate();
			if (!userRepository.existsByNickname(nickname)) {
				return nickname;
			}
		}
		throw new BusinessException(ErrorCode.INTERNAL_ERROR);
	}

	private User saveOAuthUser(String email, String nickname, LocalDateTime now) {
		try {
			return userRepository.saveAndFlush(User.createOAuthOnly(email, nickname, now));
		} catch (DataIntegrityViolationException ex) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}
	}

	private void saveSocialAccount(
		User user, OAuthProviderName provider, String providerUserId, LocalDateTime now) {
		try {
			socialAccountRepository.saveAndFlush(
				SocialAccount.create(user, provider, providerUserId, now));
		} catch (DataIntegrityViolationException ex) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}
	}

	private void checkDuplicate(String email, String nickname) {
		if (userRepository.existsByEmail(email) || userRepository.existsByNickname(nickname)) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}
	}

	private EmailVerification findAndValidateVerification(
		String tokenHash, String email, LocalDateTime now) {
		EmailVerification verification = emailVerificationRepository.findByTokenHash(tokenHash)
			.orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED));

		if (verification.getVerifiedAt() == null
			|| verification.getConsumedAt() != null
			|| verification.getTokenExpiresAt() == null
			|| !verification.getTokenExpiresAt().isAfter(now)
			|| !verification.getEmail().equals(email)) {
			throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
		}
		return verification;
	}

	private User saveUser(
		String email, String passwordHash, String nickname, LocalDateTime now) {
		try {
			return userRepository.saveAndFlush(User.create(email, passwordHash, nickname, now));
		} catch (DataIntegrityViolationException ex) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}
	}

	private String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("토큰 SHA-256 계산에 실패했습니다.", ex);
		}
	}
}
