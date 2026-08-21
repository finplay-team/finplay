// 가입 토큰 소비부터 회원·계좌·Refresh Token 저장까지 하나의 트랜잭션으로 조정하는 서비스
package com.finplay.api.domain.auth.service;

import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.EmailVerification;
import com.finplay.api.domain.auth.entity.ReauthToken;
import com.finplay.api.domain.auth.entity.RefreshToken;
import com.finplay.api.domain.auth.entity.SignupMethod;
import com.finplay.api.domain.auth.entity.SocialAccount;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.dto.response.MemberResponse;
import com.finplay.api.domain.auth.dto.response.ReauthTokenResponse;
import com.finplay.api.domain.auth.dto.response.TokenResponse;
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
		// 회원 없음·소셜 전용 가입자·비밀번호 불일치를 모두 같은 UNAUTHORIZED로 처리한다.
		// 원인별로 응답이 갈리면 이메일 존재 여부가 노출된다.
		User user = userRepository.findByEmail(email)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		// 비밀번호 보유 판정은 hasPassword() 하나로만 한다 (이슈 #122) — NULL 검사만 하면 자리표시자를 가진
		// 소셜 전용 가입자가 걸러지지 않고 뒤쪽 matches()에만 의존하게 된다. 응답은 어느 쪽이든 UNAUTHORIZED다.
		if (!user.hasPassword() || !passwordEncoder.matches(password, user.getPasswordHash())) {
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

	// 재인증은 조회와 reauth_tokens 저장만 한다 — 회원·소셜계정·계좌·시드머니를 만들거나 바꾸지 않는다 (PRD AUTH-003).
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

	// 재인증 증명 검증과 닉네임 변경을 한 트랜잭션으로 묶는다 — 닉네임 저장이 실패하면 재인증 토큰 소비도 롤백된다.
	@Transactional
	public MemberResponse changeNickname(
		Long userId, String newNickname, String currentPassword, String reauthToken) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		// 클라이언트가 채운 필드가 아니라 DB의 실제 가입 방식으로 분기한다 (필드 조작으로 비밀번호 검증을 우회할 수 없게).
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

	// 인증번호 검증·소비 → 이메일 변경 → 기존 Refresh Token 전체 폐기를 한 트랜잭션으로 묶는다.
	// 인증번호 불일치·만료·5회초과의 일반 BusinessException은 시도 횟수 증가분을 커밋하고(noRollbackFor),
	// 유니크 제약 경합에서 던지는 EmailChangeConflictException만 전체 롤백한다(rollbackFor, depth 0으로 우선 매칭).
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

	// 재인증 검증·해시 교체·Refresh Token 전체 폐기·새 토큰 쌍 발급을 한 트랜잭션으로 묶는다 — 실패하면 아무 것도 바뀌지 않는다.
	@Transactional
	public TokenResponse changePassword(Long userId, String currentPassword, String newPassword) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

		// 클라이언트가 채운 필드가 아니라 DB의 실제 가입 방식으로 분기한다.
		// OAuth 전용 회원은 바꿀 비밀번호 자체가 없으므로 재인증 실패(403)가 아니라 계정 유형 불일치(400)다.
		SignupMethod signupMethod = socialAccountRepository.findByUserId(userId)
			.map(socialAccount -> SignupMethod.fromProvider(socialAccount.getProvider()))
			.orElse(SignupMethod.EMAIL);
		if (signupMethod != SignupMethod.EMAIL) {
			throw new BusinessException(
				ErrorCode.VALIDATION_ERROR, "OAuth 전용 회원은 비밀번호를 변경할 수 없습니다.");
		}

		verifyCurrentPassword(user, currentPassword);

		// 대조가 끝난 뒤에 비교한다 — 순서를 뒤집으면 현재 비밀번호를 모르는 요청자에게도 이 분기가 노출된다.
		if (currentPassword.equals(newPassword)) {
			throw new BusinessException(
				ErrorCode.VALIDATION_ERROR, "새 비밀번호는 현재 비밀번호와 달라야 합니다.");
		}

		user.changePassword(passwordEncoder.encode(newPassword), now);
		userRepository.saveAndFlush(user);

		// 폐기가 먼저다 — 새 토큰을 먼저 저장하면 revokedAt IS NULL 조건에 그 행까지 걸려 요청 기기도 로그아웃된다.
		refreshTokenRepository.revokeAllActiveByUserId(userId, now);
		return issueTokenPair(user, now);
	}

	// 인증번호 검증·소비 → 해시 교체 → Refresh Token 전체 폐기를 한 트랜잭션으로 묶는다.
	// 검증 실패의 시도 횟수 증가분은 커밋해야 무차별 대입 방지가 유지되므로 BusinessException에 롤백하지 않는다.
	// 모든 예외 발생 지점이 소비·교체·폐기보다 앞이라 부분 성공 상태가 만들어지지 않는다.
	@Transactional(noRollbackFor = BusinessException.class)
	public void confirmPasswordReset(String email, String code, String newPassword) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = passwordResetService.validateAndConsumeCode(email, code);

		user.changePassword(passwordEncoder.encode(newPassword), now);
		userRepository.saveAndFlush(user);

		// 비로그인 흐름이라 발급할 대상 세션이 없다 — 폐기만 하고 새 토큰 쌍은 만들지 않는다(항상 전 기기 로그아웃).
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

	// 토큰 미존재·타인 소유·이미 소비·만료를 구분하지 않는다 — 사유가 갈리면 토큰의 어느 속성이 틀렸는지 노출된다.
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
