package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.auth.email.EmailSender;
import com.finplay.api.domain.auth.entity.EmailChangeVerification;
import com.finplay.api.domain.auth.entity.SocialAccount;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.repository.EmailChangeVerificationRepository;
import com.finplay.api.domain.auth.repository.ReauthTokenRepository;
import com.finplay.api.domain.auth.repository.SocialAccountRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.verification.VerificationCodePolicy;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EmailChangeServiceTest {

	private static final String SECRET = "unit-test-hmac-secret";
	private static final Long USER_ID = 1L;
	private static final String NEW_EMAIL = "new@finplay.com";
	private static final String CURRENT_PASSWORD = "raw-current-password";
	private static final String PASSWORD_HASH = "hashed-current-password";
	private static final String REAUTH_TOKEN = "reauth-token-value";
	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-25T10:30:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	@Mock
	private UserRepository userRepository;

	@Mock
	private SocialAccountRepository socialAccountRepository;

	@Mock
	private ReauthTokenRepository reauthTokenRepository;

	@Mock
	private EmailChangeVerificationRepository emailChangeVerificationRepository;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private EmailSender emailSender;

	private EmailChangeService service;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		service = new EmailChangeService(
			userRepository, socialAccountRepository, reauthTokenRepository, emailChangeVerificationRepository,
			passwordEncoder, emailSender, clock, new VerificationCodePolicy(), SECRET);
	}

	@Test
	@DisplayName("EMAIL 회원이 올바른 현재 비밀번호를 제출하면 인증번호를 저장·발송한다")
	void sendsCodeWhenEmailMemberPasswordMatches() {
		User user = emailMemberUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
		when(passwordEncoder.matches(CURRENT_PASSWORD, PASSWORD_HASH)).thenReturn(true);
		when(userRepository.existsByEmail(NEW_EMAIL)).thenReturn(false);

		service.requestEmailChange(USER_ID, NEW_EMAIL, CURRENT_PASSWORD, null);

		ArgumentCaptor<EmailChangeVerification> savedCaptor = ArgumentCaptor.forClass(EmailChangeVerification.class);
		verify(emailChangeVerificationRepository).save(savedCaptor.capture());
		verify(emailSender).sendVerificationCode(eq(NEW_EMAIL), any());

		EmailChangeVerification saved = savedCaptor.getValue();
		assertThat(saved.getUser()).isEqualTo(user);
		assertThat(saved.getNewEmail()).isEqualTo(NEW_EMAIL);
		assertThat(saved.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
	}

	@Test
	@DisplayName("EMAIL 회원이 잘못된 현재 비밀번호를 제출하면 저장·발송 없이 REAUTHENTICATION_FAILED(403)를 던진다")
	void throwsReauthenticationFailedWhenEmailMemberPasswordMismatches() {
		User user = emailMemberUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
		when(passwordEncoder.matches("wrong-password", PASSWORD_HASH)).thenReturn(false);

		assertThatThrownBy(() -> service.requestEmailChange(USER_ID, NEW_EMAIL, "wrong-password", null))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);

		verify(emailChangeVerificationRepository, never()).save(any());
		verify(emailSender, never()).sendVerificationCode(any(), any());
	}

	@Test
	@DisplayName("OAuth 전용 회원이 유효한 reauthToken을 제출하면 인증번호를 저장·발송한다")
	void sendsCodeWhenOAuthMemberReauthTokenValid() {
		User user = oauthMemberUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(socialAccountOf(user)));
		when(reauthTokenRepository.consumeIfValidForUser(eq(sha256(REAUTH_TOKEN)), eq(USER_ID), eq(NOW)))
			.thenReturn(1);
		when(userRepository.existsByEmail(NEW_EMAIL)).thenReturn(false);

		service.requestEmailChange(USER_ID, NEW_EMAIL, null, REAUTH_TOKEN);

		verify(emailChangeVerificationRepository).save(any());
		verify(emailSender).sendVerificationCode(eq(NEW_EMAIL), any());
	}

	@Test
	@DisplayName("OAuth 전용 회원의 reauthToken 소비가 실패(미존재·만료·타인 소유·이미 소비 대표값 0)하면 저장·발송 없이 REAUTHENTICATION_FAILED(403)를 던진다")
	void throwsReauthenticationFailedWhenOAuthMemberReauthTokenInvalid() {
		User user = oauthMemberUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(socialAccountOf(user)));
		when(reauthTokenRepository.consumeIfValidForUser(eq(sha256(REAUTH_TOKEN)), eq(USER_ID), eq(NOW)))
			.thenReturn(0);

		assertThatThrownBy(() -> service.requestEmailChange(USER_ID, NEW_EMAIL, null, REAUTH_TOKEN))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);

		verify(emailChangeVerificationRepository, never()).save(any());
		verifyNoInteractions(emailSender);
	}

	@Test
	@DisplayName("재인증에 성공해도 다른 회원이 이미 쓰는 새 이메일이면 저장·발송 없이 DUPLICATE_RESOURCE(409)를 던진다")
	void throwsDuplicateResourceWhenNewEmailAlreadyUsed() {
		User user = emailMemberUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
		when(passwordEncoder.matches(CURRENT_PASSWORD, PASSWORD_HASH)).thenReturn(true);
		when(userRepository.existsByEmail(NEW_EMAIL)).thenReturn(true);

		assertThatThrownBy(() -> service.requestEmailChange(USER_ID, NEW_EMAIL, CURRENT_PASSWORD, null))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.DUPLICATE_RESOURCE);

		verify(emailChangeVerificationRepository, never()).save(any());
		verifyNoInteractions(emailSender);
	}

	@Test
	@DisplayName("60초 이내 재요청이면 저장·발송 없이 TOO_MANY_REQUESTS(429)를 던진다")
	void throwsTooManyRequestsWhenResentWithin60Seconds() {
		User user = emailMemberUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
		when(passwordEncoder.matches(CURRENT_PASSWORD, PASSWORD_HASH)).thenReturn(true);
		when(userRepository.existsByEmail(NEW_EMAIL)).thenReturn(false);
		when(emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(USER_ID, NOW.minusSeconds(60)))
			.thenReturn(1L);

		assertThatThrownBy(() -> service.requestEmailChange(USER_ID, NEW_EMAIL, CURRENT_PASSWORD, null))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		verify(emailChangeVerificationRepository, never()).save(any());
		verifyNoInteractions(emailSender);
	}

	@Test
	@DisplayName("최근 1시간 발송이 5회 이상이면 저장·발송 없이 TOO_MANY_REQUESTS(429)를 던진다")
	void throwsTooManyRequestsWhenHourlyLimitReached() {
		User user = emailMemberUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
		when(passwordEncoder.matches(CURRENT_PASSWORD, PASSWORD_HASH)).thenReturn(true);
		when(userRepository.existsByEmail(NEW_EMAIL)).thenReturn(false);
		when(emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(USER_ID, NOW.minusSeconds(60)))
			.thenReturn(0L);
		when(emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(USER_ID, NOW.minusHours(1)))
			.thenReturn(5L);

		assertThatThrownBy(() -> service.requestEmailChange(USER_ID, NEW_EMAIL, CURRENT_PASSWORD, null))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		verify(emailChangeVerificationRepository, never()).save(any());
		verifyNoInteractions(emailSender);
	}

	@Test
	@DisplayName("최근 하루 발송이 10회 이상이면 저장·발송 없이 TOO_MANY_REQUESTS(429)를 던진다")
	void throwsTooManyRequestsWhenDailyLimitReached() {
		User user = emailMemberUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
		when(passwordEncoder.matches(CURRENT_PASSWORD, PASSWORD_HASH)).thenReturn(true);
		when(userRepository.existsByEmail(NEW_EMAIL)).thenReturn(false);
		when(emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(USER_ID, NOW.minusSeconds(60)))
			.thenReturn(0L);
		when(emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(USER_ID, NOW.minusHours(1)))
			.thenReturn(0L);
		when(emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(USER_ID, NOW.minusDays(1)))
			.thenReturn(10L);

		assertThatThrownBy(() -> service.requestEmailChange(USER_ID, NEW_EMAIL, CURRENT_PASSWORD, null))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		verify(emailChangeVerificationRepository, never()).save(any());
		verifyNoInteractions(emailSender);
	}

	@Test
	@DisplayName("각 창이 한도 바로 아래면(1시간 4회·하루 9회) 정상 발송된다 — 경계 통과")
	void sendsWhenCountsAreJustBelowLimits() {
		User user = emailMemberUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
		when(passwordEncoder.matches(CURRENT_PASSWORD, PASSWORD_HASH)).thenReturn(true);
		when(userRepository.existsByEmail(NEW_EMAIL)).thenReturn(false);
		when(emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(USER_ID, NOW.minusSeconds(60)))
			.thenReturn(0L);
		when(emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(USER_ID, NOW.minusHours(1)))
			.thenReturn(4L);
		when(emailChangeVerificationRepository.countByUserIdAndCreatedAtAfter(USER_ID, NOW.minusDays(1)))
			.thenReturn(9L);

		service.requestEmailChange(USER_ID, NEW_EMAIL, CURRENT_PASSWORD, null);

		verify(emailChangeVerificationRepository).save(any());
		verify(emailSender).sendVerificationCode(eq(NEW_EMAIL), any());
	}

	@Test
	@DisplayName("재발송 시 같은 회원·같은 새 이메일의 이전 미소비·유효 인증번호는 즉시 무효화된다")
	void expiresPreviousCodeForSameUserAndNewEmailOnResend() {
		User user = emailMemberUser();
		when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
		when(socialAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
		when(passwordEncoder.matches(CURRENT_PASSWORD, PASSWORD_HASH)).thenReturn(true);
		when(userRepository.existsByEmail(NEW_EMAIL)).thenReturn(false);

		EmailChangeVerification previous = EmailChangeVerification.create(
			user, NEW_EMAIL, "old-hash", NOW.plusMinutes(3), NOW.minusMinutes(2));
		when(emailChangeVerificationRepository
			.findByUserIdAndNewEmailAndConsumedAtIsNullAndExpiresAtAfter(USER_ID, NEW_EMAIL, NOW))
			.thenReturn(List.of(previous));

		assertThat(previous.getExpiresAt()).isEqualTo(NOW.plusMinutes(3));

		service.requestEmailChange(USER_ID, NEW_EMAIL, CURRENT_PASSWORD, null);

		assertThat(previous.getExpiresAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("확인 대상 인증번호 요청 이력이 없으면 EMAIL_VERIFICATION_FAILED(400)를 던진다")
	void throwsEmailVerificationFailedWhenNoRequestFound() {
		when(emailChangeVerificationRepository.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(USER_ID, NEW_EMAIL))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.validateAndConsumeCode(USER_ID, NEW_EMAIL, "123456"))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
	}

	@Test
	@DisplayName("다른 회원이 남의 새 이메일 조합을 확인하려 하면 요청 없음과 동일하게 EMAIL_VERIFICATION_FAILED(400)를 던진다")
	void throwsEmailVerificationFailedWhenRequestedByOtherUser() {
		Long otherUserId = 999L;
		when(emailChangeVerificationRepository
			.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(otherUserId, NEW_EMAIL))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.validateAndConsumeCode(otherUserId, NEW_EMAIL, "123456"))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
	}

	@Test
	@DisplayName("이미 소비된 인증번호로 재확인하면 EMAIL_VERIFICATION_FAILED(400)를 던진다")
	void throwsEmailVerificationFailedWhenAlreadyConsumed() {
		User user = emailMemberUser();
		EmailChangeVerification verification = EmailChangeVerification.create(
			user, NEW_EMAIL, hmac("123456"), NOW.plusMinutes(5), NOW.minusMinutes(1));
		verification.consume(NOW.minusSeconds(30));
		when(emailChangeVerificationRepository.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(USER_ID, NEW_EMAIL))
			.thenReturn(Optional.of(verification));

		assertThatThrownBy(() -> service.validateAndConsumeCode(USER_ID, NEW_EMAIL, "123456"))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
	}

	@Test
	@DisplayName("인증번호가 만료됐으면(자연 만료·재발송 무효화 동일) EMAIL_VERIFICATION_FAILED(400)를 던진다")
	void throwsEmailVerificationFailedWhenExpired() {
		User user = emailMemberUser();
		EmailChangeVerification verification = EmailChangeVerification.create(
			user, NEW_EMAIL, hmac("123456"), NOW.minusMinutes(1), NOW.minusMinutes(6));
		when(emailChangeVerificationRepository.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(USER_ID, NEW_EMAIL))
			.thenReturn(Optional.of(verification));

		assertThatThrownBy(() -> service.validateAndConsumeCode(USER_ID, NEW_EMAIL, "123456"))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
	}

	@Test
	@DisplayName("5회 시도에 도달한 상태에서 확인하면 시도 횟수를 증가시키고 즉시 만료 처리한 뒤 TOO_MANY_REQUESTS(429)를 던진다")
	void throwsTooManyRequestsAndExpiresWhenAttemptCountExceeded() {
		User user = emailMemberUser();
		EmailChangeVerification verification = EmailChangeVerification.create(
			user, NEW_EMAIL, hmac("123456"), NOW.plusMinutes(5), NOW.minusMinutes(1));
		for (int i = 0; i < 5; i++) {
			verification.incrementAttemptCount();
		}
		when(emailChangeVerificationRepository.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(USER_ID, NEW_EMAIL))
			.thenReturn(Optional.of(verification));

		assertThatThrownBy(() -> service.validateAndConsumeCode(USER_ID, NEW_EMAIL, "123456"))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		assertThat(verification.getAttemptCount()).isEqualTo(6);
		assertThat(verification.getExpiresAt()).isEqualTo(NOW);
		assertThat(verification.getConsumedAt()).isNull();
	}

	@Test
	@DisplayName("코드가 일치하지 않으면 시도 횟수를 증가시키고 EMAIL_VERIFICATION_FAILED(400)를 던진다")
	void throwsEmailVerificationFailedAndIncrementsAttemptCountWhenCodeMismatches() {
		User user = emailMemberUser();
		EmailChangeVerification verification = EmailChangeVerification.create(
			user, NEW_EMAIL, hmac("123456"), NOW.plusMinutes(5), NOW.minusMinutes(1));
		when(emailChangeVerificationRepository.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(USER_ID, NEW_EMAIL))
			.thenReturn(Optional.of(verification));

		assertThatThrownBy(() -> service.validateAndConsumeCode(USER_ID, NEW_EMAIL, "654321"))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);

		assertThat(verification.getAttemptCount()).isEqualTo(1);
		assertThat(verification.getConsumedAt()).isNull();
	}

	@Test
	@DisplayName("유효한 인증번호에 올바른 코드로 확인하면 인증번호를 소비 처리한다")
	void consumesVerificationWhenCodeMatches() {
		User user = emailMemberUser();
		EmailChangeVerification verification = EmailChangeVerification.create(
			user, NEW_EMAIL, hmac("123456"), NOW.plusMinutes(5), NOW.minusMinutes(1));
		when(emailChangeVerificationRepository.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(USER_ID, NEW_EMAIL))
			.thenReturn(Optional.of(verification));

		service.validateAndConsumeCode(USER_ID, NEW_EMAIL, "123456");

		assertThat(verification.getConsumedAt()).isEqualTo(NOW);
		assertThat(verification.getAttemptCount()).isZero();
	}

	private User emailMemberUser() {
		User user = User.create("email-member@finplay.com", PASSWORD_HASH, "email-nick", NOW.minusDays(10));
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

	private User oauthMemberUser() {
		User user = User.create("oauth-member@finplay.com", null, "oauth-nick", NOW.minusDays(10));
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

	private SocialAccount socialAccountOf(User user) {
		return SocialAccount.create(user, OAuthProviderName.KAKAO, "provider-user-id", NOW.minusDays(10));
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String hmac(String code) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
