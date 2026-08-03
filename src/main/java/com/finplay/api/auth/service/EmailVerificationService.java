// 인증번호 발송 제한 판정·생성·HMAC 저장·이전 코드 무효화·발송을 담당하는 서비스
package com.finplay.api.auth.service;

import com.finplay.api.auth.domain.EmailVerification;
import com.finplay.api.auth.dto.response.SignupTokenResponse;
import com.finplay.api.auth.email.EmailSender;
import com.finplay.api.auth.repository.EmailVerificationRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.verification.VerificationCodeHasher;
import com.finplay.api.auth.verification.VerificationCodePolicy;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
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
	// 가입 인증 토큰(SIGNUP_TOKEN) 생성 전용 — 인증번호 생성은 codePolicy가 담당한다.
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

	// 인증번호를 생성·저장하고 대상 이메일로 발송한다. 이전 미확인 코드는 만료 처리해 유효한 코드는 항상 최대 1개다.
	@Transactional
	public void sendVerificationCode(String email) {
		if (userRepository.existsByEmail(email)) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}

		LocalDateTime now = LocalDateTime.now(clock);
		checkSendRateLimit(email, now);
		expirePreviousCodes(email, now);

		String code = codePolicy.generateCode();
		EmailVerification verification = EmailVerification.create(
			email, codeHasher.hmac(code), codePolicy.expiresAt(now), now);
		emailVerificationRepository.save(verification);

		// 발송은 저장 이후에 한다. 발송 실패 시 트랜잭션이 롤백되어 저장·이전 코드 만료가 함께 되돌려진다.
		emailSender.sendVerificationCode(email, code);
	}

	// BusinessException에도 시도 횟수와 만료 상태가 커밋되어 무차별 대입을 차단한다.
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

	private void checkSendRateLimit(String email, LocalDateTime now) {
		if (emailVerificationRepository.countByEmailAndCreatedAtAfter(
			email, now.minusSeconds(VerificationCodePolicy.RESEND_INTERVAL_SECONDS)) > 0) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (emailVerificationRepository.countByEmailAndCreatedAtAfter(email,
			now.minusHours(1)) >= VerificationCodePolicy.HOURLY_LIMIT) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (emailVerificationRepository.countByEmailAndCreatedAtAfter(email,
			now.minusDays(1)) >= VerificationCodePolicy.DAILY_LIMIT) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
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
