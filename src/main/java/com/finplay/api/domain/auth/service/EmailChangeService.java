package com.finplay.api.domain.auth.service;

import com.finplay.api.domain.auth.email.EmailSender;
import com.finplay.api.domain.auth.entity.EmailChangeVerification;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.EmailChangeVerificationRepository;
import com.finplay.api.domain.auth.repository.ReauthTokenRepository;
import com.finplay.api.domain.auth.repository.SocialAccountRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.verification.VerificationCodeHasher;
import com.finplay.api.domain.auth.verification.VerificationCodePolicy;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EmailChangeService {

	private final UserRepository userRepository;
	private final SocialAccountRepository socialAccountRepository;
	private final ReauthTokenRepository reauthTokenRepository;
	private final EmailChangeVerificationRepository emailChangeVerificationRepository;
	private final PasswordEncoder passwordEncoder;
	private final EmailSender emailSender;
	private final Clock clock;
	private final VerificationCodePolicy codePolicy;
	private final VerificationCodeHasher codeHasher;

	public EmailChangeService(
		UserRepository userRepository,
		SocialAccountRepository socialAccountRepository,
		ReauthTokenRepository reauthTokenRepository,
		EmailChangeVerificationRepository emailChangeVerificationRepository,
		PasswordEncoder passwordEncoder,
		EmailSender emailSender,
		Clock clock,
		VerificationCodePolicy codePolicy,
		@Value("${EMAIL_VERIFICATION_SECRET}")
		String emailVerificationSecret) {
		this.userRepository = userRepository;
		this.socialAccountRepository = socialAccountRepository;
		this.reauthTokenRepository = reauthTokenRepository;
		this.emailChangeVerificationRepository = emailChangeVerificationRepository;
		this.passwordEncoder = passwordEncoder;
		this.emailSender = emailSender;
		this.clock = clock;
		this.codePolicy = codePolicy;
		this.codeHasher = new VerificationCodeHasher(emailVerificationSecret);
	}

	@Transactional
	public void requestEmailChange(Long userId, String newEmail, String currentPassword, String reauthToken) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

		verifyReauthProof(user, currentPassword, reauthToken);

		if (userRepository.existsByEmail(newEmail)) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}

		LocalDateTime now = LocalDateTime.now(clock);
		codePolicy.checkSendRateLimit(
			now, since -> emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(userId, since));
		expirePreviousCodes(userId, newEmail, now);

		String code = codePolicy.generateCode();
		EmailChangeVerification verification = EmailChangeVerification.create(
			user, newEmail, codeHasher.hmac(code), codePolicy.expiresAt(now), now);
		emailChangeVerificationRepository.save(verification);

		emailSender.sendVerificationCode(newEmail, code);
	}

	public void validateAndConsumeCode(Long userId, String newEmail, String code) {
		LocalDateTime now = LocalDateTime.now(clock);
		EmailChangeVerification verification = emailChangeVerificationRepository
			.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(userId, newEmail)
			.orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED));

		if (verification.getConsumedAt() != null || !verification.getExpiresAt().isAfter(now)) {
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

		verification.consume(now);
	}

	private void verifyReauthProof(User user, String currentPassword, String reauthToken) {
		boolean isEmailMember = socialAccountRepository.findByUserId(user.getId()).isEmpty();
		if (isEmailMember) {
			if (!StringUtils.hasText(currentPassword)
				|| !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
				throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
			}
			return;
		}

		if (!StringUtils.hasText(reauthToken)) {
			throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
		}
		LocalDateTime now = LocalDateTime.now(clock);
		int consumed = reauthTokenRepository.consumeIfValidForUser(sha256(reauthToken), user.getId(), now);
		if (consumed != 1) {
			throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
		}
	}

	private void expirePreviousCodes(Long userId, String newEmail, LocalDateTime now) {
		List<EmailChangeVerification> previous = emailChangeVerificationRepository
			.findByUserIdAndNewEmailAndConsumedAtIsNullAndExpiresAtAfter(userId, newEmail, now);
		for (EmailChangeVerification verification : previous) {
			verification.expire(now);
		}
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("재인증 토큰 SHA-256 계산에 실패했습니다.", ex);
		}
	}
}
