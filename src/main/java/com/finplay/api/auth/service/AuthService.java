// 가입 토큰 소비부터 회원·계좌·Refresh Token 저장까지 하나의 트랜잭션으로 조정하는 서비스
package com.finplay.api.auth.service;

import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.EmailVerification;
import com.finplay.api.auth.domain.RefreshToken;
import com.finplay.api.auth.domain.SignupMethod;
import com.finplay.api.auth.domain.SocialAccount;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.dto.response.MemberResponse;
import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.oauth.OAuthNicknameGenerator;
import com.finplay.api.auth.oauth.OAuthProviderName;
import com.finplay.api.auth.oauth.OAuthUserDto;
import com.finplay.api.auth.repository.EmailVerificationRepository;
import com.finplay.api.auth.repository.RefreshTokenRepository;
import com.finplay.api.auth.repository.SocialAccountRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.IssuedTokenPair;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

	private static final String OAUTH_ONLY_PASSWORD_SENTINEL = "{oauth-only}";
	private static final int MAX_NICKNAME_ATTEMPTS = 5;

	private final UserRepository userRepository;
	private final EmailVerificationRepository emailVerificationRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final SocialAccountRepository socialAccountRepository;
	private final PasswordEncoder passwordEncoder;
	private final AccountService accountService;
	private final JwtTokenProvider jwtTokenProvider;
	private final OAuthNicknameGenerator oauthNicknameGenerator;
	private final Clock clock;

	public AuthService(
		UserRepository userRepository,
		EmailVerificationRepository emailVerificationRepository,
		RefreshTokenRepository refreshTokenRepository,
		SocialAccountRepository socialAccountRepository,
		PasswordEncoder passwordEncoder,
		AccountService accountService,
		JwtTokenProvider jwtTokenProvider,
		OAuthNicknameGenerator oauthNicknameGenerator,
		Clock clock) {
		this.userRepository = userRepository;
		this.emailVerificationRepository = emailVerificationRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.socialAccountRepository = socialAccountRepository;
		this.passwordEncoder = passwordEncoder;
		this.accountService = accountService;
		this.jwtTokenProvider = jwtTokenProvider;
		this.oauthNicknameGenerator = oauthNicknameGenerator;
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
		// 회원 없음·소셜 전용 가입자·비밀번호 불일치를 모두 같은 UNAUTHORIZED로 처리한다.
		// 원인별로 응답이 갈리면 이메일 존재 여부가 노출된다.
		User user = userRepository.findByEmail(email)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		if (user.getPasswordHash() == null
			|| !passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		// 기존 Refresh Token은 폐기하지 않고 행을 추가만 한다 (다중 기기 로그인 유지, 폐기는 재발급·로그아웃 소관).
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
			return userRepository.saveAndFlush(
				User.create(email, OAUTH_ONLY_PASSWORD_SENTINEL, nickname, now));
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
