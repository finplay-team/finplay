package com.finplay.api.domain.auth.service;

import com.finplay.api.domain.auth.email.EmailSender;
import com.finplay.api.domain.auth.entity.PasswordResetVerification;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.PasswordResetVerificationRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.verification.VerificationCodeHasher;
import com.finplay.api.domain.auth.verification.VerificationCodePolicy;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetService {

	private static final String NOT_FOUND_MESSAGE = "가입되지 않은 이메일입니다.";

	private final UserRepository userRepository;
	private final PasswordResetVerificationRepository passwordResetVerificationRepository;
	private final EmailSender emailSender;
	private final Clock clock;
	private final VerificationCodePolicy codePolicy;
	private final VerificationCodeHasher codeHasher;

	public PasswordResetService(
		UserRepository userRepository,
		PasswordResetVerificationRepository passwordResetVerificationRepository,
		EmailSender emailSender,
		Clock clock,
		VerificationCodePolicy codePolicy,
		@Value("${PASSWORD_RESET_SECRET}")
		String passwordResetSecret) {
		this.userRepository = userRepository;
		this.passwordResetVerificationRepository = passwordResetVerificationRepository;
		this.emailSender = emailSender;
		this.clock = clock;
		this.codePolicy = codePolicy;
		this.codeHasher = new VerificationCodeHasher(passwordResetSecret);
	}

	@Transactional(noRollbackFor = BusinessException.class)
	public void sendResetCode(String email) {
		LocalDateTime now = LocalDateTime.now(clock);
		codePolicy.checkSendRateLimit(
			now, since -> passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(email, since));

		User user = userRepository.findByEmail(email).orElse(null);
		if (user == null) {
			passwordResetVerificationRepository.save(PasswordResetVerification.createRejected(email, now));
			throw new BusinessException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE);
		}
		if (!user.hasPassword()) {
			passwordResetVerificationRepository.save(PasswordResetVerification.createRejected(email, now));
			throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ONLY);
		}

		expirePreviousCodes(email, now);

		String code = codePolicy.generateCode();
		PasswordResetVerification verification = PasswordResetVerification.create(
			email, codeHasher.hmac(code), codePolicy.expiresAt(now), now);
		passwordResetVerificationRepository.save(verification);

		emailSender.sendPasswordResetCode(email, code);
	}

	public User validateAndConsumeCode(String email, String code) {
		LocalDateTime now = LocalDateTime.now(clock);
		PasswordResetVerification verification = passwordResetVerificationRepository
			.findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(email)
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

		User user = userRepository.findByEmail(email)
			.orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED));
		if (!user.hasPassword()) {
			throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ONLY);
		}

		verification.consume(now);
		return user;
	}

	private void expirePreviousCodes(String email, LocalDateTime now) {
		List<PasswordResetVerification> previous = passwordResetVerificationRepository
			.findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(email, now);
		for (PasswordResetVerification verification : previous) {
			verification.expire(now);
		}
	}
}
