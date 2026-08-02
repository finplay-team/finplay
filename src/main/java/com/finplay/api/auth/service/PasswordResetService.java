// 비밀번호 재설정 인증번호의 발송 제한 판정·대상 회원 판별·생성·HMAC 저장·이전 코드 무효화·발송과 확인 시 검증·소비를 담당하는 서비스
package com.finplay.api.auth.service;

import com.finplay.api.auth.domain.PasswordResetVerification;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.email.EmailSender;
import com.finplay.api.auth.repository.PasswordResetVerificationRepository;
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
public class PasswordResetService {

	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final int CODE_BOUND = 1_000_000; // 6자리(000000~999999) 난수 상한.
	private static final String CODE_FORMAT = "%06d";
	private static final int CODE_TTL_MINUTES = 5;
	private static final int RESEND_INTERVAL_SECONDS = 60;
	private static final int HOURLY_LIMIT = 5;
	private static final int DAILY_LIMIT = 10;
	private static final int MAX_VERIFICATION_ATTEMPTS = 5;
	private static final String NOT_FOUND_MESSAGE = "가입되지 않은 이메일입니다.";

	private final UserRepository userRepository;
	private final PasswordResetVerificationRepository passwordResetVerificationRepository;
	private final EmailSender emailSender;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();
	private final byte[] hmacKey;

	public PasswordResetService(
		UserRepository userRepository,
		PasswordResetVerificationRepository passwordResetVerificationRepository,
		EmailSender emailSender,
		Clock clock,
		@Value("${PASSWORD_RESET_SECRET}")
		String passwordResetSecret) {
		this.userRepository = userRepository;
		this.passwordResetVerificationRepository = passwordResetVerificationRepository;
		this.emailSender = emailSender;
		this.clock = clock;
		this.hmacKey = passwordResetSecret.getBytes(StandardCharsets.UTF_8);
	}

	// 재설정 인증번호를 생성·저장하고 가입 이메일로 발송한다.
	// 거부(404·409) 요청도 발송 제한 집계용 행으로 남겨야 하므로 BusinessException에 롤백하지 않는다.
	// 발송 실패는 BusinessException이 아니므로 기본 롤백 규칙에 걸려 저장·이전 코드 무효화가 함께 되돌려진다.
	@Transactional(noRollbackFor = BusinessException.class)
	public void sendResetCode(String email) {
		LocalDateTime now = LocalDateTime.now(clock);
		// 발송 제한을 가장 먼저 판정한다 — 존재 여부를 먼저 보면 제한을 초과한 요청자도 계정 상태를 알 수 있다.
		// 429는 집계 행을 남기지 않는다(남기면 하루 10회 제한이 영구 차단으로 변한다).
		checkSendRateLimit(email, now);

		User user = userRepository.findByEmail(email).orElse(null);
		if (user == null) {
			passwordResetVerificationRepository.save(PasswordResetVerification.createRejected(email, now));
			throw new BusinessException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE);
		}
		// 재설정할 비밀번호가 있는지를 묻는 판별이므로 social_accounts 연결 여부가 아니라 비밀번호 보유 여부로 본다.
		// OAuth 전용 가입자는 password_hash에 자리표시자가 채워져 있어 NULL 검사만으로는 걸러지지 않는다.
		if (!user.hasPassword()) {
			passwordResetVerificationRepository.save(PasswordResetVerification.createRejected(email, now));
			throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ONLY);
		}

		expirePreviousCodes(email, now);

		String code = generateCode();
		PasswordResetVerification verification = PasswordResetVerification.create(
			email, hmac(code), now.plusMinutes(CODE_TTL_MINUTES), now);
		passwordResetVerificationRepository.save(verification);

		// 발송은 저장 이후에 한다 — 발송 실패 시 저장과 이전 코드 무효화가 함께 롤백된다.
		// 가입 인증·이메일 변경과 다른 전용 문구로 보낸다 — 수신자가 재설정 시도임을 알아채야 한다.
		emailSender.sendPasswordResetCode(email, code);
	}

	// D1: 조회 → 소비 상태·만료 → 시도 횟수 초과 → 코드 일치 → 회원 존재 → 비밀번호 보유 순으로 검증하고 마지막에 소비한다.
	// 계정 상태 판정을 인증번호 검증 뒤에 두는 이유(D2) — 이 경로에는 발송 제한이 없어, 순서를 뒤집으면
	// 아무 이메일로 호출해 404/409만 보고 가입 여부·소셜 전용 여부를 무제한 스캔하는 계정 열거 오라클이 된다.
	// 트랜잭션 경계는 갖지 않는다 — 호출자인 AuthService.confirmPasswordReset의 트랜잭션 안에서 실행된다.
	public User validateAndConsumeCode(String email, String code) {
		LocalDateTime now = LocalDateTime.now(clock);
		// 실제로 발송된 행 중 최신 1건만 본다 — 거부 행(code_hash NULL)이 더 최신일 수 있어 반드시 걸러낸다.
		PasswordResetVerification verification = passwordResetVerificationRepository
			.findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(email)
			.orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED));

		// 자연 만료·재발송 무효화·5회 초과 무효화가 모두 expires_at으로 수렴한다.
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

		// 여기부터는 인증번호를 맞힌 요청자다 — 이제서야 계정 상태를 드러낸다.
		// 미가입은 404가 아니라 400이다(발송 엔드포인트와 의도적으로 다르다). 발송 후 이메일이 바뀐 경우만 도달하는 방어 분기다.
		User user = userRepository.findByEmail(email)
			.orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED));
		if (!user.hasPassword()) {
			throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ONLY);
		}

		// 모든 판정을 통과한 뒤에만 소비한다 — 위에서 던지면 인증번호가 낭비되지 않는다.
		verification.consume(now);
		return user;
	}

	// 발송 제한은 이메일 주소 단위이며, 미가입·소셜 전용으로 거부된 요청 행도 함께 집계한다.
	private void checkSendRateLimit(String email, LocalDateTime now) {
		if (passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(
			email, now.minusSeconds(RESEND_INTERVAL_SECONDS)) > 0) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(email,
			now.minusHours(1)) >= HOURLY_LIMIT) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(email, now.minusDays(1)) >= DAILY_LIMIT) {
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
	}

	// 재발송 시 같은 이메일의 이전 유효 코드를 무효화한다 — 실제로 발송된 행만 대상이다.
	private void expirePreviousCodes(String email, LocalDateTime now) {
		List<PasswordResetVerification> previous = passwordResetVerificationRepository
			.findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(email, now);
		for (PasswordResetVerification verification : previous) {
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
			throw new IllegalStateException("비밀번호 재설정 인증번호 HMAC 계산에 실패했습니다.", ex);
		}
	}
}
