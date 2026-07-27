// 이메일 변경 인증번호 발송 서비스의 재인증 판별·중복 검사·발송 제한·이전 코드 무효화를 검증하는 단위 테스트 (ADR-0003)
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.auth.domain.EmailChangeVerification;
import com.finplay.api.auth.domain.SocialAccount;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.email.EmailSender;
import com.finplay.api.auth.oauth.OAuthProviderName;
import com.finplay.api.auth.repository.EmailChangeVerificationRepository;
import com.finplay.api.auth.repository.ReauthTokenRepository;
import com.finplay.api.auth.repository.SocialAccountRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
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
	// Clock.fixed로 고정한 기준 시각. 발송 제한 임계값(60초·1시간·하루)이 이 값 기준으로 계산된다.
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
			passwordEncoder, emailSender, clock, SECRET);
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

		// 만료 처리 전에는 아직 유효(만료 시각이 기준 시각 이후).
		assertThat(previous.getExpiresAt()).isEqualTo(NOW.plusMinutes(3));

		service.requestEmailChange(USER_ID, NEW_EMAIL, CURRENT_PASSWORD, null);

		// expire(now)가 호출되어 만료 시각이 기준 시각으로 당겨진다 = 즉시 무효화.
		assertThat(previous.getExpiresAt()).isEqualTo(NOW);
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
}
