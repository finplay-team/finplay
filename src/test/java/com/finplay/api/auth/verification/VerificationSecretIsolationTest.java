// 가입 인증(EMAIL_VERIFICATION_SECRET)과 비밀번호 재설정(PASSWORD_RESET_SECRET)의 HMAC 시크릿 분리를 고정하는 가드 테스트 (#121 D8)
package com.finplay.api.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.finplay.api.auth.domain.EmailVerification;
import com.finplay.api.auth.domain.PasswordResetVerification;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.email.EmailSender;
import com.finplay.api.auth.repository.EmailVerificationRepository;
import com.finplay.api.auth.repository.PasswordResetVerificationRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.service.EmailVerificationService;
import com.finplay.api.auth.service.PasswordResetService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 공통화(VerificationCodeHasher) 이후에도 두 경로가 서로 다른 시크릿을 소유하는지 확인한다.
// 배선이 뒤바뀌면 한쪽 시크릿으로 만든 인증번호가 다른 쪽 확인 경로에서 통과해 버리므로 여기서 잡는다.
@ExtendWith(MockitoExtension.class)
class VerificationSecretIsolationTest {

	private static final String EMAIL_VERIFICATION_SECRET = "guard-email-verification-secret";
	private static final String PASSWORD_RESET_SECRET = "guard-password-reset-secret";
	private static final String EMAIL = "guard@finplay.com";
	private static final String CODE = "123456";
	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-03T10:30:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private static final VerificationCodeHasher EMAIL_HASHER = new VerificationCodeHasher(EMAIL_VERIFICATION_SECRET);
	private static final VerificationCodeHasher RESET_HASHER = new VerificationCodeHasher(PASSWORD_RESET_SECRET);

	@Mock
	private UserRepository userRepository;

	@Mock
	private EmailVerificationRepository emailVerificationRepository;

	@Mock
	private PasswordResetVerificationRepository passwordResetVerificationRepository;

	@Mock
	private EmailSender emailSender;

	private EmailVerificationService emailVerificationService;
	private PasswordResetService passwordResetService;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		VerificationCodePolicy codePolicy = new VerificationCodePolicy();
		// 프로덕션과 같은 배선 — 가입 인증은 EMAIL_VERIFICATION_SECRET, 재설정은 PASSWORD_RESET_SECRET을 받는다.
		emailVerificationService = new EmailVerificationService(
			userRepository, emailVerificationRepository, emailSender, clock, codePolicy, EMAIL_VERIFICATION_SECRET);
		passwordResetService = new PasswordResetService(
			userRepository, passwordResetVerificationRepository, emailSender, clock, codePolicy,
			PASSWORD_RESET_SECRET);
	}

	@Test
	@DisplayName("같은 인증번호라도 시크릿이 다르면 해시가 다르다")
	void sameCodeHashesDifferentlyPerSecret() {
		assertThat(EMAIL_HASHER.hmac(CODE)).isNotEqualTo(RESET_HASHER.hmac(CODE));
		assertThat(EMAIL_HASHER.hmac(CODE)).hasSize(64);
		assertThat(RESET_HASHER.hmac(CODE)).hasSize(64);
	}

	@Test
	@DisplayName("가입 인증 확인은 재설정 시크릿으로 만든 해시를 거부한다")
	void signupConfirmRejectsCodeHashedWithPasswordResetSecret() {
		EmailVerification verification = EmailVerification.create(
			EMAIL, RESET_HASHER.hmac(CODE), NOW.plusMinutes(4), NOW.minusMinutes(1));
		when(emailVerificationRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL))
			.thenReturn(Optional.of(verification));

		assertThatThrownBy(() -> emailVerificationService.confirmVerificationCode(EMAIL, CODE))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);

		// 해시 대조 분기까지 도달했다는 증거 — 만료·소비 같은 앞선 분기에서 걸린 것이 아니다.
		assertThat(verification.getAttemptCount()).isEqualTo(1);
		assertThat(verification.getVerifiedAt()).isNull();
	}

	@Test
	@DisplayName("가입 인증 확인은 가입 인증 시크릿으로 만든 해시를 통과시킨다")
	void signupConfirmAcceptsCodeHashedWithEmailVerificationSecret() {
		EmailVerification verification = EmailVerification.create(
			EMAIL, EMAIL_HASHER.hmac(CODE), NOW.plusMinutes(4), NOW.minusMinutes(1));
		when(emailVerificationRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL))
			.thenReturn(Optional.of(verification));

		assertThat(emailVerificationService.confirmVerificationCode(EMAIL, CODE)).isNotNull();
		assertThat(verification.getVerifiedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("재설정 확인은 가입 인증 시크릿으로 만든 해시를 거부한다")
	void passwordResetConfirmRejectsCodeHashedWithEmailVerificationSecret() {
		PasswordResetVerification verification = PasswordResetVerification.create(
			EMAIL, EMAIL_HASHER.hmac(CODE), NOW.plusMinutes(4), NOW.minusMinutes(1));
		when(passwordResetVerificationRepository.findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(EMAIL))
			.thenReturn(Optional.of(verification));

		assertThatThrownBy(() -> passwordResetService.validateAndConsumeCode(EMAIL, CODE))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);

		assertThat(verification.getAttemptCount()).isEqualTo(1);
		assertThat(verification.getConsumedAt()).isNull();
	}

	@Test
	@DisplayName("재설정 확인은 재설정 시크릿으로 만든 해시를 통과시킨다")
	void passwordResetConfirmAcceptsCodeHashedWithPasswordResetSecret() {
		PasswordResetVerification verification = PasswordResetVerification.create(
			EMAIL, RESET_HASHER.hmac(CODE), NOW.plusMinutes(4), NOW.minusMinutes(1));
		when(passwordResetVerificationRepository.findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(EMAIL))
			.thenReturn(Optional.of(verification));
		when(userRepository.findByEmail(EMAIL))
			.thenReturn(Optional.of(User.create(EMAIL, "stored-password-hash", "guard-user", NOW.minusDays(10))));

		assertThat(passwordResetService.validateAndConsumeCode(EMAIL, CODE)).isNotNull();
		assertThat(verification.getConsumedAt()).isEqualTo(NOW);
	}
}
