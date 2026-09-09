package com.finplay.api.domain.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.auth.email.EmailSender;
import com.finplay.api.domain.auth.entity.EmailVerification;
import com.finplay.api.domain.auth.entity.PasswordResetVerification;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.EmailVerificationRepository;
import com.finplay.api.domain.auth.repository.PasswordResetVerificationRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.service.EmailVerificationService;
import com.finplay.api.domain.auth.service.PasswordResetService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
