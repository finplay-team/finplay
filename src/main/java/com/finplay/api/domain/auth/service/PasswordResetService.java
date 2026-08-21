// 비밀번호 재설정 인증번호의 발송 제한 판정·대상 회원 판별·생성·HMAC 저장·이전 코드 무효화·발송과 확인 시 검증·소비를 담당하는 서비스
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

	// 재설정 인증번호를 생성·저장하고 가입 이메일로 발송한다.
	// 거부(404·409) 요청도 발송 제한 집계용 행으로 남겨야 하므로 BusinessException에 롤백하지 않는다.
	// 발송 실패는 BusinessException이 아니므로 기본 롤백 규칙에 걸려 저장·이전 코드 무효화가 함께 되돌려진다.
	@Transactional(noRollbackFor = BusinessException.class)
	public void sendResetCode(String email) {
		LocalDateTime now = LocalDateTime.now(clock);
		// 발송 제한을 가장 먼저 판정한다 — 존재 여부를 먼저 보면 제한을 초과한 요청자도 계정 상태를 알 수 있다.
		// 429는 집계 행을 남기지 않는다(남기면 하루 10회 제한이 영구 차단으로 변한다).
		// 집계는 이메일 주소 단위이며, 미가입·소셜 전용으로 거부된 요청 행(code_hash NULL)도 함께 센다.
		codePolicy.checkSendRateLimit(
			now, since -> passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(email, since));

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

		String code = codePolicy.generateCode();
		PasswordResetVerification verification = PasswordResetVerification.create(
			email, codeHasher.hmac(code), codePolicy.expiresAt(now), now);
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
		if (codePolicy.isAttemptLimitReached(verification.getAttemptCount())) {
			verification.incrementAttemptCount();
			verification.expire(now);
			throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
		}
		if (!verification.getCodeHash().equals(codeHasher.hmac(code))) {
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

	// 재발송 시 같은 이메일의 이전 유효 코드를 무효화한다 — 실제로 발송된 행만 대상이다.
	private void expirePreviousCodes(String email, LocalDateTime now) {
		List<PasswordResetVerification> previous = passwordResetVerificationRepository
			.findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(email, now);
		for (PasswordResetVerification verification : previous) {
			verification.expire(now);
		}
	}
}
