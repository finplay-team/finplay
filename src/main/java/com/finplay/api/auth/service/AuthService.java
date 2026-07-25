// 가입 토큰 소비부터 회원·계좌·Refresh Token 저장까지 하나의 트랜잭션으로 조정하는 서비스
package com.finplay.api.auth.service;

import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.EmailVerification;
import com.finplay.api.auth.domain.RefreshToken;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.repository.EmailVerificationRepository;
import com.finplay.api.auth.repository.RefreshTokenRepository;
import com.finplay.api.auth.repository.UserRepository;
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

	private final UserRepository userRepository;
	private final EmailVerificationRepository emailVerificationRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final AccountService accountService;
	private final JwtTokenProvider jwtTokenProvider;
	private final Clock clock;

	public AuthService(
		UserRepository userRepository,
		EmailVerificationRepository emailVerificationRepository,
		RefreshTokenRepository refreshTokenRepository,
		PasswordEncoder passwordEncoder,
		AccountService accountService,
		JwtTokenProvider jwtTokenProvider,
		Clock clock) {
		this.userRepository = userRepository;
		this.emailVerificationRepository = emailVerificationRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.accountService = accountService;
		this.jwtTokenProvider = jwtTokenProvider;
		this.clock = clock;
	}

	@Transactional
	public TokenResponse signup(
		String email, String nickname, String password, String signupVerificationToken) {
		checkDuplicate(email, nickname);
		LocalDateTime now = LocalDateTime.now(clock);
		String tokenHash = sha256(signupVerificationToken);
		findAndValidateVerification(tokenHash, email, now);

		int consumed = emailVerificationRepository.consumeValidToken(tokenHash, now);
		if (consumed != 1) {
			throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
		}

		User user = saveUser(email, passwordEncoder.encode(password), nickname, now);
		accountService.createAccountsFor(user);

		IssuedTokenPair tokens = jwtTokenProvider.issue(user.getId(), user.getRole());
		refreshTokenRepository.save(RefreshToken.create(
			user, sha256(tokens.refreshToken()), tokens.refreshTokenExpiresAt(), now));
		return TokenResponse.from(tokens);
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
