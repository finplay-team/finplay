// 인증번호 발송 제한 판정·생성·HMAC 저장·이전 코드 무효화·발송을 담당하는 서비스
package com.finplay.api.auth.service;

import com.finplay.api.auth.domain.EmailVerification;
import com.finplay.api.auth.email.EmailSender;
import com.finplay.api.auth.repository.EmailVerificationRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {

	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final int CODE_BOUND = 1_000_000; // 6자리(000000~999999) 난수 상한.
	private static final String CODE_FORMAT = "%06d";
	private static final int CODE_TTL_MINUTES = 5;
	private static final int RESEND_INTERVAL_SECONDS = 60;
	private static final int HOURLY_LIMIT = 5;
	private static final int DAILY_LIMIT = 10;

	private final UserRepository userRepository;
	private final EmailVerificationRepository emailVerificationRepository;
	private final EmailSender emailSender;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();
	private final byte[] hmacKey;

	public EmailVerificationService(
		UserRepository userRepository,
		EmailVerificationRepository emailVerificationRepository,
		EmailSender emailSender,
		Clock clock,
		@Value("${EMAIL_VERIFICATION_SECRET:finplay-local-dev-email-verification-secret-change-in-prod}")
		String emailVerificationSecret) {
		this.userRepository = userRepository;
		this.emailVerificationRepository = emailVerificationRepository;
		this.emailSender = emailSender;
		this.clock = clock;
		this.hmacKey = emailVerificationSecret.getBytes(StandardCharsets.UTF_8);
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

		String code = generateCode();
		EmailVerification verification = EmailVerification.create(
			email, hmac(code), now.plusMinutes(CODE_TTL_MINUTES), now);
		emailVerificationRepository.save(verification);

		// 발송은 저장 이후에 한다. 발송 실패 시 트랜잭션이 롤백되어 저장·이전 코드 만료가 함께 되돌려진다.
		emailSender.sendVerificationCode(email, code);
	}

	private void checkSendRateLimit(String email, LocalDateTime now) {
		if (emailVerificationRepository.countByEmailAndCreatedAtAfter(
			email, now.minusSeconds(RESEND_INTERVAL_SECONDS)) > 0) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (emailVerificationRepository.countByEmailAndCreatedAtAfter(email, now.minusHours(1)) >= HOURLY_LIMIT) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (emailVerificationRepository.countByEmailAndCreatedAtAfter(email, now.minusDays(1)) >= DAILY_LIMIT) {
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
}
