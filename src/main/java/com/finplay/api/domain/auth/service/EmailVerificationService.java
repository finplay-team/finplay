package com.finplay.api.domain.auth.service;

import com.finplay.api.domain.auth.dto.response.SignupTokenResponse;
import com.finplay.api.domain.auth.email.EmailSender;
import com.finplay.api.domain.auth.entity.EmailVerification;
import com.finplay.api.domain.auth.repository.EmailVerificationRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.verification.VerificationCodeHasher;
import com.finplay.api.domain.auth.verification.VerificationCodePolicy;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {

	private static final int SIGNUP_TOKEN_BYTES = 32;
	private static final int SIGNUP_TOKEN_TTL_MINUTES = 30;

	private final UserRepository userRepository;
	private final EmailVerificationRepository emailVerificationRepository;
	private final EmailSender emailSender;
	private final Clock clock;
	private final VerificationCodePolicy codePolicy;
	private final SecureRandom secureRandom = new SecureRandom();
	private final VerificationCodeHasher codeHasher;

	public EmailVerificationService(
		UserRepository userRepository,
		EmailVerificationRepository emailVerificationRepository,
		EmailSender emailSender,
		Clock clock,
		VerificationCodePolicy codePolicy,
		@Value("${EMAIL_VERIFICATION_SECRET}")
		String emailVerificationSecret) {
		this.userRepository = userRepository;
		this.emailVerificationRepository = emailVerificationRepository;
		this.emailSender = emailSender;
		this.clock = clock;
		this.codePolicy = codePolicy;
		this.codeHasher = new VerificationCodeHasher(emailVerificationSecret);
	}

	@Transactional
	public void sendVerificationCode(String email) {
		if (userRepository.existsByEmail(email)) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}

		LocalDateTime now = LocalDateTime.now(clock);
		codePolicy.checkSendRateLimit(
			now, since -> emailVerificationRepository.countByEmailAndCreatedAtAfter(email, since));
		expirePreviousCodes(email, now);

		String code = codePolicy.generateCode();
		EmailVerification verification = EmailVerification.create(
			email, codeHasher.hmac(code), codePolicy.expiresAt(now), now);
		emailVerificationRepository.save(verification);

		emailSender.sendVerificationCode(email, code);
	}

	@Transactional(noRollbackFor = BusinessException.class)
	public SignupTokenResponse confirmVerificationCode(String email, String code) {
		LocalDateTime now = LocalDateTime.now(clock);
		EmailVerification verification = emailVerificationRepository.findFirstByEmailOrderByCreatedAtDesc(email)
			.orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED));

		if (!verification.getExpiresAt().isAfter(now) || verification.getVerifiedAt() != null) {
			throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED);
		}

		if (codePolicy.isAttemptLimitReached(verification.getAttemptCount())) {
			verification.incrementAttemptCount();
			verification.expire(now);
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (!verification.getCodeHash().equals(codeHasher.hmac(code))) {
			verification.incrementAttemptCount();
			throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED);
		}

		String signupVerificationToken = generateSignupVerificationToken();
		verification.confirm(
			now,
			sha256(signupVerificationToken),
			now.plusMinutes(SIGNUP_TOKEN_TTL_MINUTES));
		return new SignupTokenResponse(signupVerificationToken, SIGNUP_TOKEN_TTL_MINUTES * 60L);
	}

	private void expirePreviousCodes(String email, LocalDateTime now) {
		List<EmailVerification> previous = emailVerificationRepository
			.findByEmailAndVerifiedAtIsNullAndExpiresAtAfter(email, now);
		for (EmailVerification verification : previous) {
			verification.expire(now);
		}
	}

	private String generateSignupVerificationToken() {
		byte[] bytes = new byte[SIGNUP_TOKEN_BYTES];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("가입 인증 토큰 SHA-256 계산에 실패했습니다.", ex);
		}
	}
}
