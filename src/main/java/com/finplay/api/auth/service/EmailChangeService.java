// 새 이메일 재인증·중복·발송 제한 판정, 인증번호 발송과 확인 시 검증·소비를 담당하는 서비스
package com.finplay.api.auth.service;

import com.finplay.api.auth.domain.EmailChangeVerification;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.email.EmailSender;
import com.finplay.api.auth.repository.EmailChangeVerificationRepository;
import com.finplay.api.auth.repository.ReauthTokenRepository;
import com.finplay.api.auth.repository.SocialAccountRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class EmailChangeService {

	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final int CODE_BOUND = 1_000_000; // 6자리(000000~999999) 난수 상한.
	private static final String CODE_FORMAT = "%06d";
	private static final int CODE_TTL_MINUTES = 5;
	private static final int RESEND_INTERVAL_SECONDS = 60;
	private static final int HOURLY_LIMIT = 5;
	private static final int DAILY_LIMIT = 10;
	private static final int MAX_VERIFICATION_ATTEMPTS = 5;

	private final UserRepository userRepository;
	private final SocialAccountRepository socialAccountRepository;
	private final ReauthTokenRepository reauthTokenRepository;
	private final EmailChangeVerificationRepository emailChangeVerificationRepository;
	private final PasswordEncoder passwordEncoder;
	private final EmailSender emailSender;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();
	private final byte[] hmacKey;

	public EmailChangeService(
		UserRepository userRepository,
		SocialAccountRepository socialAccountRepository,
		ReauthTokenRepository reauthTokenRepository,
		EmailChangeVerificationRepository emailChangeVerificationRepository,
		PasswordEncoder passwordEncoder,
		EmailSender emailSender,
		Clock clock,
		@Value("${EMAIL_VERIFICATION_SECRET}")
		String emailVerificationSecret) {
		this.userRepository = userRepository;
		this.socialAccountRepository = socialAccountRepository;
		this.reauthTokenRepository = reauthTokenRepository;
		this.emailChangeVerificationRepository = emailChangeVerificationRepository;
		this.passwordEncoder = passwordEncoder;
		this.emailSender = emailSender;
		this.clock = clock;
		this.hmacKey = emailVerificationSecret.getBytes(StandardCharsets.UTF_8);
	}

	// 재인증 증명(비밀번호/reauthToken) → 새 이메일 중복 → 발송 제한 순서로 판정한 뒤 인증번호를 발송한다.
	@Transactional
	public void requestEmailChange(Long userId, String newEmail, String currentPassword, String reauthToken) {
		User user = userRepository.findById(userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

		verifyReauthProof(user, currentPassword, reauthToken);

		if (userRepository.existsByEmail(newEmail)) {
			throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
		}

		LocalDateTime now = LocalDateTime.now(clock);
		checkSendRateLimit(userId, now);
		expirePreviousCodes(userId, newEmail, now);

		String code = generateCode();
		EmailChangeVerification verification = EmailChangeVerification.create(
			user, newEmail, hmac(code), now.plusMinutes(CODE_TTL_MINUTES), now);
		emailChangeVerificationRepository.save(verification);

		// 발송은 저장 이후에 한다. 발송 실패 시 트랜잭션이 롤백되어 저장·이전 코드 만료·토큰 소비가 함께 되돌려진다.
		emailSender.sendVerificationCode(newEmail, code);
	}

	// D1: 조회 → 소비 상태 → 만료 → 시도 횟수 초과 → 코드 일치 순으로 검증하고 성공 시 소비 처리한다.
	// 트랜잭션 경계는 갖지 않는다 — 호출자인 AuthService.confirmEmailChange의 트랜잭션 안에서 실행된다.
	public void validateAndConsumeCode(Long userId, String newEmail, String code) {
		LocalDateTime now = LocalDateTime.now(clock);
		EmailChangeVerification verification = emailChangeVerificationRepository
			.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(userId, newEmail)
			.orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED));

		if (verification.getConsumedAt() != null || !verification.getExpiresAt().isAfter(now)) {
			throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED);
		}
		if (verification.getAttemptCount() >= MAX_VERIFICATION_ATTEMPTS) {
			verification.incrementAttemptCount();
			verification.expire(now);
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (!verification.getCodeHash().equals(hmac(code))) {
			verification.incrementAttemptCount();
			throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED);
		}

		verification.consume(now);
	}

	// EMAIL 회원은 현재 비밀번호, OAuth 전용 회원은 reauthToken으로 재인증한다. 실패 사유는 구분하지 않고 403으로 통일한다.
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

	// 발송 제한 판정 — 대상 이메일과 무관하게 회원(userId) 단위로 60초/1시간 5회/하루 10회를 합산한다.
	private void checkSendRateLimit(Long userId, LocalDateTime now) {
		if (emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(
			userId, now.minusSeconds(RESEND_INTERVAL_SECONDS)) > 0) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(userId,
			now.minusHours(1)) >= HOURLY_LIMIT) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(userId, now.minusDays(1)) >= DAILY_LIMIT) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
	}

	// 재발송 시 같은 회원·같은 새 이메일의 이전 인증번호를 즉시 무효화한다.
	private void expirePreviousCodes(Long userId, String newEmail, LocalDateTime now) {
		List<EmailChangeVerification> previous = emailChangeVerificationRepository
			.findByUserIdAndNewEmailAndConsumedAtIsNullAndExpiresAtAfter(userId, newEmail, now);
		for (EmailChangeVerification verification : previous) {
			verification.expire(now);
		}
	}

	private String generateCode() {
		return String.format(CODE_FORMAT, secureRandom.nextInt(CODE_BOUND));
	}

	private String hmac(String code) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(hmacKey, HMAC_ALGORITHM));
			return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("인증번호 HMAC 계산에 실패했습니다.", ex);
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
