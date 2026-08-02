// 비밀번호 재설정 발송의 판정 순서(제한 → 존재 → 가입 방식)·거부 요청 집계 행·HMAC 저장(원문 미저장)·이전 코드 무효화를 검증하는 단위 테스트 (ADR-0003)
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.auth.domain.PasswordResetVerification;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.email.EmailSender;
import com.finplay.api.auth.repository.PasswordResetVerificationRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.nio.charset.StandardCharsets;
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

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

	private static final String SECRET = "unit-test-password-reset-secret";
	// 가입 인증번호와 시크릿을 공유하지 않는다(D7)는 것을 대조하기 위한 다른 시크릿.
	private static final String OTHER_SECRET = "unit-test-email-verification-secret";
	private static final String EMAIL = "reset@finplay.com";
	// Clock.fixed로 고정한 기준 시각. 발송 제한 창(60초·1시간·하루)이 이 값 기준으로 계산된다.
	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-01T10:30:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	@Mock
	private UserRepository userRepository;

	@Mock
	private PasswordResetVerificationRepository passwordResetVerificationRepository;

	@Mock
	private EmailSender emailSender;

	private PasswordResetService service;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		service = new PasswordResetService(
			userRepository, passwordResetVerificationRepository, emailSender, clock, SECRET);
	}

	@Test
	@DisplayName("정상 발송: 6자리 코드를 발송하고 저장되는 값은 원문이 아닌 전용 시크릿 기반 HMAC이다")
	void sendsCodeAndStoresHmacInsteadOfRawCode() {
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(passwordUser()));

		service.sendResetCode(EMAIL);

		ArgumentCaptor<PasswordResetVerification> savedCaptor = ArgumentCaptor
			.forClass(PasswordResetVerification.class);
		ArgumentCaptor<String> sentCodeCaptor = ArgumentCaptor.forClass(String.class);
		verify(passwordResetVerificationRepository).save(savedCaptor.capture());
		verify(emailSender).sendVerificationCode(eq(EMAIL), sentCodeCaptor.capture());

		String sentCode = sentCodeCaptor.getValue();
		PasswordResetVerification saved = savedCaptor.getValue();

		assertThat(sentCode).matches("\\d{6}");
		// 저장 인자에 원문이 그대로 들어가면 안 된다 — 실제 HMAC-SHA-256(hex 64자) 결과여야 한다.
		assertThat(saved.getCodeHash()).isNotEqualTo(sentCode);
		assertThat(saved.getCodeHash()).doesNotContain(sentCode);
		assertThat(saved.getCodeHash()).hasSize(64);
		assertThat(saved.getCodeHash()).isEqualTo(hmac(SECRET, sentCode));
		// 다른 시크릿으로는 같은 해시가 나오지 않는다 = 주입된 전용 시크릿을 실제로 쓴다.
		assertThat(saved.getCodeHash()).isNotEqualTo(hmac(OTHER_SECRET, sentCode));
		// TTL·시각은 고정 Clock 기준이다.
		assertThat(saved.getEmail()).isEqualTo(EMAIL);
		assertThat(saved.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
		assertThat(saved.getLastSentAt()).isEqualTo(NOW);
		assertThat(saved.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("미가입 이메일은 404지만 발송 제한 집계용 거부 행은 남기고 메일은 보내지 않는다")
	void rejectsUnknownEmailWithNotFoundAndStoresRejectedRowWithoutSending() {
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.sendResetCode(EMAIL))
			.isInstanceOf(BusinessException.class)
			.hasMessage("가입되지 않은 이메일입니다.")
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.NOT_FOUND);

		assertRejectedRowSaved();
		verify(emailSender, never()).sendVerificationCode(any(), any());
	}

	@Test
	@DisplayName("비밀번호가 없는 소셜 전용 회원은 409 SOCIAL_ACCOUNT_ONLY이고 거부 행만 남는다")
	void rejectsSocialOnlyAccountWithConflictAndStoresRejectedRowWithoutSending() {
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(socialOnlyUser()));

		assertThatThrownBy(() -> service.sendResetCode(EMAIL))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.SOCIAL_ACCOUNT_ONLY);

		assertRejectedRowSaved();
		verify(emailSender, never()).sendVerificationCode(any(), any());
	}

	@Test
	@DisplayName("회귀: password_hash가 자리표시자인 OAuth 가입자는 NULL이 아니어도 409로 거부되고 메일이 나가지 않는다")
	void rejectsOAuthUserWhosePasswordHashIsSentinelRatherThanNull() {
		User oauthUser = User.create(EMAIL, User.OAUTH_ONLY_PASSWORD_SENTINEL, "oauth-user", NOW.minusDays(10));
		// 실제 OAuth 가입자는 password_hash가 채워져 있다 — `passwordHash == null` 판별로는 이 회원을 거르지 못한다.
		assertThat(oauthUser.getPasswordHash()).isNotNull();
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(oauthUser));

		assertThatThrownBy(() -> service.sendResetCode(EMAIL))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.SOCIAL_ACCOUNT_ONLY);

		// 판별이 NULL 검사로 되돌아가면 여기서 실제 인증번호가 발송되어 실패한다.
		verify(emailSender, never()).sendVerificationCode(any(), any());
		assertRejectedRowSaved();
	}

	@Test
	@DisplayName("발송 제한을 존재 확인보다 먼저 판정한다 — 미가입 이메일이라도 제한 초과면 404가 아니라 429다")
	void checksSendRateLimitBeforeLookingUpUserSoEnumerationIsBlocked() {
		// 미가입 이메일이지만 60초 창에 이미 요청이 있다.
		when(passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusSeconds(60)))
			.thenReturn(1L);

		assertThatThrownBy(() -> service.sendResetCode(EMAIL))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		// 존재 여부 조회 자체가 일어나지 않아야 계정 상태가 드러나지 않는다.
		verify(userRepository, never()).findByEmail(any());
		verify(passwordResetVerificationRepository, never()).save(any());
		verify(emailSender, never()).sendVerificationCode(any(), any());
	}

	@Test
	@DisplayName("60초 이내 재요청은 429이며 집계 행을 남기지 않는다")
	void throwsTooManyRequestsWhenResentWithin60SecondsAndSavesNothing() {
		when(passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusSeconds(60)))
			.thenReturn(1L);

		assertTooManyRequestsWithoutAnySideEffect();
	}

	@Test
	@DisplayName("최근 1시간 요청이 5회면 429이며 집계 행을 남기지 않는다")
	void throwsTooManyRequestsWhenHourlyLimitReachedAndSavesNothing() {
		// 60초 창은 비어 있고 1시간 창만 한도(5)에 도달한 상태.
		when(passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusSeconds(60)))
			.thenReturn(0L);
		when(passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusHours(1)))
			.thenReturn(5L);

		assertTooManyRequestsWithoutAnySideEffect();
	}

	@Test
	@DisplayName("최근 하루 요청이 10회면 429이며 집계 행을 남기지 않는다")
	void throwsTooManyRequestsWhenDailyLimitReachedAndSavesNothing() {
		// 60초·1시간 창은 통과하고 하루 창만 한도(10)에 도달한 상태.
		when(passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusSeconds(60)))
			.thenReturn(0L);
		when(passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusHours(1)))
			.thenReturn(0L);
		when(passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusDays(1)))
			.thenReturn(10L);

		assertTooManyRequestsWithoutAnySideEffect();
	}

	@Test
	@DisplayName("각 창이 한도 바로 아래면(1시간 4회·하루 9회) 정상 발송된다 — 경계 통과")
	void sendsWhenCountsAreJustBelowLimits() {
		when(passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusSeconds(60)))
			.thenReturn(0L);
		when(passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusHours(1)))
			.thenReturn(4L);
		when(passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusDays(1)))
			.thenReturn(9L);
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(passwordUser()));

		service.sendResetCode(EMAIL);

		verify(passwordResetVerificationRepository).save(any());
		verify(emailSender).sendVerificationCode(eq(EMAIL), any());
	}

	@Test
	@DisplayName("재발송 시 같은 이메일의 이전 유효 코드는 기준 시각으로 즉시 무효화된다")
	void expiresPreviousValidCodesOnResend() {
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(passwordUser()));
		PasswordResetVerification previous = PasswordResetVerification
			.create(EMAIL, "old-hash", NOW.plusMinutes(4), NOW.minusMinutes(1));
		when(passwordResetVerificationRepository
			.findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(EMAIL, NOW))
			.thenReturn(List.of(previous));

		// 무효화 전에는 아직 유효하다(만료 시각이 기준 시각 이후).
		assertThat(previous.getExpiresAt()).isEqualTo(NOW.plusMinutes(4));

		service.sendResetCode(EMAIL);

		assertThat(previous.getExpiresAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("거부 경로에서는 이전 코드 무효화 조회조차 하지 않는다 — 무효화 대상은 발송에 성공한 경우뿐이다")
	void doesNotTouchPreviousCodesOnRejectedPaths() {
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(socialOnlyUser()));

		assertThatThrownBy(() -> service.sendResetCode(EMAIL)).isInstanceOf(BusinessException.class);

		verify(passwordResetVerificationRepository, never())
			.findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(any(), any());
	}

	@Test
	@DisplayName("메일 발송 실패 예외는 삼켜지지 않고 그대로 전파된다 — 저장·무효화 롤백은 트랜잭션에 맡긴다")
	void propagatesEmailSenderFailure() {
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(passwordUser()));
		doThrow(new IllegalStateException("메일 발송 실패"))
			.when(emailSender).sendVerificationCode(eq(EMAIL), any());

		assertThatThrownBy(() -> service.sendResetCode(EMAIL))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("메일 발송 실패");

		// 발송은 저장 이후에 일어나므로 저장 호출 자체는 있었어야 한다(롤백은 트랜잭션 경계의 몫이다).
		verify(passwordResetVerificationRepository).save(any());
	}

	@Test
	@DisplayName("두 번 발송하면 각 저장 행의 해시가 그때 발송된 코드의 HMAC과 각각 일치한다")
	void storesHashMatchingTheCodeActuallySentOnEachSend() {
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(passwordUser()));

		service.sendResetCode(EMAIL);
		service.sendResetCode(EMAIL);

		ArgumentCaptor<String> sentCodeCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<PasswordResetVerification> savedCaptor = ArgumentCaptor
			.forClass(PasswordResetVerification.class);
		verify(emailSender, times(2)).sendVerificationCode(eq(EMAIL), sentCodeCaptor.capture());
		verify(passwordResetVerificationRepository, times(2)).save(savedCaptor.capture());

		List<String> sentCodes = sentCodeCaptor.getAllValues();
		List<PasswordResetVerification> saved = savedCaptor.getAllValues();
		for (int i = 0; i < 2; i++) {
			assertThat(sentCodes.get(i)).matches("\\d{6}");
			assertThat(saved.get(i).getCodeHash()).isEqualTo(hmac(SECRET, sentCodes.get(i)));
		}
	}

	private void assertTooManyRequestsWithoutAnySideEffect() {
		assertThatThrownBy(() -> service.sendResetCode(EMAIL))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		// 429는 행을 남기지 않는다 — 남기면 하루 10회 제한이 사실상 영구 차단으로 변한다(D5).
		verify(passwordResetVerificationRepository, never()).save(any());
		verify(emailSender, never()).sendVerificationCode(any(), any());
	}

	private void assertRejectedRowSaved() {
		ArgumentCaptor<PasswordResetVerification> savedCaptor = ArgumentCaptor
			.forClass(PasswordResetVerification.class);
		verify(passwordResetVerificationRepository).save(savedCaptor.capture());

		PasswordResetVerification saved = savedCaptor.getValue();
		assertThat(saved.getEmail()).isEqualTo(EMAIL);
		// 발송하지 않았으므로 코드·만료·발송시각이 없어야 무효화 대상 조회에서 제외된다.
		assertThat(saved.getCodeHash()).isNull();
		assertThat(saved.getExpiresAt()).isNull();
		assertThat(saved.getLastSentAt()).isNull();
		assertThat(saved.getCreatedAt()).isEqualTo(NOW);
	}

	private static User passwordUser() {
		return User.create(EMAIL, "stored-password-hash", "reset-user", NOW.minusDays(10));
	}

	// 프로덕션의 OAuth 가입자는 password_hash가 NULL이 아니라 자리표시자다 (AuthService.saveOAuthUser).
	// NULL로 만들면 실제로 존재하지 않는 회원 형태라 409 분기가 도달 불가여도 테스트가 통과해 버린다.
	private static User socialOnlyUser() {
		return User.create(EMAIL, User.OAUTH_ONLY_PASSWORD_SENTINEL, "social-user", NOW.minusDays(10));
	}

	private static String hmac(String secret, String code) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
