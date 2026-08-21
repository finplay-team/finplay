// 비밀번호 재설정 발송의 판정 순서(제한 → 존재 → 가입 방식)·거부 요청 집계 행·HMAC 저장(원문 미저장)·이전 코드 무효화와
// 확인의 판정 순서(인증번호 → 계정 상태)·시도 횟수 증가·성공 시에만 소비를 검증하는 단위 테스트 (ADR-0003)
package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.auth.email.EmailSender;
import com.finplay.api.domain.auth.entity.PasswordResetVerification;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.PasswordResetVerificationRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.verification.VerificationCodePolicy;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
import org.springframework.test.util.ReflectionTestUtils;

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
			userRepository, passwordResetVerificationRepository, emailSender, clock, new VerificationCodePolicy(),
			SECRET);
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
		verify(emailSender).sendPasswordResetCode(eq(EMAIL), sentCodeCaptor.capture());

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
		verifyNoInteractions(emailSender);
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
		verifyNoInteractions(emailSender);
	}

	@Test
	@DisplayName("회귀: password_hash가 자리표시자인 OAuth 가입자는 NULL이 아니어도 409로 거부되고 메일이 나가지 않는다")
	void rejectsOAuthUserWhosePasswordHashIsSentinelRatherThanNull() {
		User oauthUser = User.createOAuthOnly(EMAIL, "oauth-user", NOW.minusDays(10));
		// 실제 OAuth 가입자는 password_hash가 채워져 있다 — `passwordHash == null` 판별로는 이 회원을 거르지 못한다.
		assertThat(oauthUser.getPasswordHash()).isNotNull();
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(oauthUser));

		assertThatThrownBy(() -> service.sendResetCode(EMAIL))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.SOCIAL_ACCOUNT_ONLY);

		// 판별이 NULL 검사로 되돌아가면 여기서 실제 인증번호가 발송되어 실패한다.
		verifyNoInteractions(emailSender);
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
		verifyNoInteractions(emailSender);
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
		verify(emailSender).sendPasswordResetCode(eq(EMAIL), any());
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
			.when(emailSender).sendPasswordResetCode(eq(EMAIL), any());

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
		verify(emailSender, times(2)).sendPasswordResetCode(eq(EMAIL), sentCodeCaptor.capture());
		verify(passwordResetVerificationRepository, times(2)).save(savedCaptor.capture());

		List<String> sentCodes = sentCodeCaptor.getAllValues();
		List<PasswordResetVerification> saved = savedCaptor.getAllValues();
		for (int i = 0; i < 2; i++) {
			assertThat(sentCodes.get(i)).matches("\\d{6}");
			assertThat(saved.get(i).getCodeHash()).isEqualTo(hmac(SECRET, sentCodes.get(i)));
		}
	}

	@Test
	@DisplayName("확인: 발송 행이 없으면 400 EMAIL_VERIFICATION_FAILED이고 계정 조회조차 하지 않는다")
	void validateFailsWithVerificationFailedWhenNoSentRowExists() {
		when(passwordResetVerificationRepository.findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(EMAIL))
			.thenReturn(Optional.empty());

		assertVerificationFailed("123456");

		// 요청 이력이 없다는 사실만으로 계정 상태를 조회하면 계정 열거 오라클이 된다.
		verify(userRepository, never()).findByEmail(any());
	}

	@Test
	@DisplayName("확인: 이미 소비된 인증번호는 400이고 시도 횟수도 오르지 않는다")
	void validateFailsWhenVerificationAlreadyConsumed() {
		PasswordResetVerification consumed = validVerification("123456");
		ReflectionTestUtils.setField(consumed, "consumedAt", NOW.minusMinutes(1));
		stubLatestVerification(consumed);

		assertVerificationFailed("123456");

		assertThat(consumed.getAttemptCount()).isZero();
		verify(userRepository, never()).findByEmail(any());
	}

	@Test
	@DisplayName("확인: 만료된 인증번호는 400이며 만료 시각이 기준 시각과 같은 경계값도 만료로 본다")
	void validateFailsWhenVerificationExpiredIncludingExactBoundary() {
		// expires_at == now — after가 아니므로 이미 만료다.
		PasswordResetVerification boundary = PasswordResetVerification
			.create(EMAIL, hmac(SECRET, "123456"), NOW, NOW.minusMinutes(5));
		stubLatestVerification(boundary);

		assertVerificationFailed("123456");

		assertThat(boundary.getAttemptCount()).isZero();
		assertThat(boundary.getConsumedAt()).isNull();
		verify(userRepository, never()).findByEmail(any());
	}

	@Test
	@DisplayName("확인: 재발송으로 무효화된 이전 인증번호는 정답이어도 400이다")
	void validateFailsForPreviousCodeInvalidatedByResend() {
		PasswordResetVerification previous = validVerification("123456");
		// 재발송 시 expire(now)로 무효화된 상태를 그대로 재현한다.
		previous.expire(NOW);
		stubLatestVerification(previous);

		assertVerificationFailed("123456");

		assertThat(previous.getConsumedAt()).isNull();
		verify(userRepository, never()).findByEmail(any());
	}

	@Test
	@DisplayName("확인: 시도 횟수가 5회에 도달하면 429이고 증가와 함께 인증번호가 즉시 무효화된다")
	void validateThrowsTooManyRequestsAndExpiresImmediatelyAtFifthAttempt() {
		PasswordResetVerification verification = validVerification("123456");
		for (int i = 0; i < 5; i++) {
			verification.incrementAttemptCount();
		}
		stubLatestVerification(verification);

		// 정답을 보내도 5회 한도가 먼저 걸린다.
		assertThatThrownBy(() -> service.validateAndConsumeCode(EMAIL, "123456"))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		assertThat(verification.getAttemptCount()).isEqualTo(6);
		assertThat(verification.getExpiresAt()).isEqualTo(NOW);
		assertThat(verification.getConsumedAt()).isNull();
		verify(userRepository, never()).findByEmail(any());
	}

	@Test
	@DisplayName("확인: 5회 초과로 무효화된 뒤에는 같은 인증번호에 정답을 넣어도 400으로 바뀐다")
	void validateFailsWithVerificationFailedAfterCodeWasInvalidatedByAttemptLimit() {
		PasswordResetVerification verification = validVerification("123456");
		for (int i = 0; i < 5; i++) {
			verification.incrementAttemptCount();
		}
		stubLatestVerification(verification);

		assertThatThrownBy(() -> service.validateAndConsumeCode(EMAIL, "123456"))
			.isInstanceOf(BusinessException.class);
		// 두 번째 호출은 expire(now)로 만료된 행을 보므로 429가 아니라 400이다.
		assertVerificationFailed("123456");

		assertThat(verification.getConsumedAt()).isNull();
	}

	@Test
	@DisplayName("확인: 시도 횟수가 4회면 아직 한도 아래라 정답이 통과한다 — 경계")
	void validateSucceedsWhenAttemptCountIsJustBelowLimit() {
		PasswordResetVerification verification = validVerification("123456");
		for (int i = 0; i < 4; i++) {
			verification.incrementAttemptCount();
		}
		stubLatestVerification(verification);
		User user = passwordUser();
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

		User result = service.validateAndConsumeCode(EMAIL, "123456");

		assertThat(result).isSameAs(user);
		assertThat(verification.getConsumedAt()).isEqualTo(NOW);
		// 성공은 시도 횟수를 올리지 않는다.
		assertThat(verification.getAttemptCount()).isEqualTo(4);
	}

	@Test
	@DisplayName("확인: 코드가 불일치하면 400이고 시도 횟수만 오른다 — 소비·만료는 그대로다")
	void validateFailsAndOnlyIncrementsAttemptCountWhenCodeDoesNotMatch() {
		PasswordResetVerification verification = validVerification("123456");
		stubLatestVerification(verification);

		assertVerificationFailed("999999");

		assertThat(verification.getAttemptCount()).isEqualTo(1);
		assertThat(verification.getConsumedAt()).isNull();
		assertThat(verification.getExpiresAt()).isEqualTo(NOW.plusMinutes(4));
	}

	@Test
	@DisplayName("확인: 코드 불일치는 계정 조회보다 먼저 걸린다 — 미가입 이메일에 틀린 코드를 보내도 계정 조회가 없다")
	void validateRejectsWrongCodeBeforeTouchingUserRepository() {
		stubLatestVerification(validVerification("123456"));

		assertVerificationFailed("999999");

		// 계정 상태 판정이 코드 검증보다 앞서면 코드를 모르는 요청자도 가입 여부를 알아낼 수 있다 (D2).
		// 오류 코드는 순서가 뒤집혀도 같을 수 있으므로(미가입도 400) 조회 호출 자체의 부재로 고정한다.
		verify(userRepository, never()).findByEmail(any());
	}

	@Test
	@DisplayName("확인: 정답이지만 미가입 이메일이면 404가 아니라 400 EMAIL_VERIFICATION_FAILED다")
	void validateFailsWithVerificationFailedNotNotFoundWhenUserIsMissing() {
		PasswordResetVerification verification = validVerification("123456");
		stubLatestVerification(verification);
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

		// 발송 경로(#115)는 같은 상황에서 404지만 확인 경로는 의도적으로 400이다 — 여기엔 발송 제한이 없어
		// 404로 구분하면 횟수 제한 없는 계정 열거 오라클이 된다 (D2).
		assertVerificationFailed("123456");

		assertThat(verification.getConsumedAt()).isNull();
	}

	@Test
	@DisplayName("확인: 비밀번호가 없는 소셜 전용 계정은 409이며 인증번호는 소비되지 않는다")
	void validateFailsWithSocialAccountOnlyAndDoesNotConsumeCode() {
		PasswordResetVerification verification = validVerification("123456");
		stubLatestVerification(verification);
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(socialOnlyUser()));

		assertThatThrownBy(() -> service.validateAndConsumeCode(EMAIL, "123456"))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.SOCIAL_ACCOUNT_ONLY);

		// 소비는 모든 판정 이후에만 일어난다 — 여기서 소비되면 인증번호가 낭비된다.
		assertThat(verification.getConsumedAt()).isNull();
		assertThat(verification.getAttemptCount()).isZero();
	}

	@Test
	@DisplayName("확인: 모든 판정을 통과하면 인증번호를 소비하고 대상 회원을 반환한다")
	void validateConsumesCodeAndReturnsTargetUserOnSuccess() {
		PasswordResetVerification verification = validVerification("123456");
		stubLatestVerification(verification);
		User user = passwordUser();
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

		User result = service.validateAndConsumeCode(EMAIL, "123456");

		assertThat(result).isSameAs(user);
		assertThat(verification.getConsumedAt()).isEqualTo(NOW);
		assertThat(verification.getAttemptCount()).isZero();
		// 소비만 하고 만료를 앞당기지는 않는다 — 재사용 차단은 consumed_at이 담당한다.
		assertThat(verification.getExpiresAt()).isEqualTo(NOW.plusMinutes(4));
	}

	@Test
	@DisplayName("확인: 같은 인증번호를 두 번 쓰면 두 번째는 소비 상태로 걸려 400이다")
	void validateRejectsSecondUseOfTheSameCode() {
		PasswordResetVerification verification = validVerification("123456");
		stubLatestVerification(verification);
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(passwordUser()));

		service.validateAndConsumeCode(EMAIL, "123456");

		assertVerificationFailed("123456");
	}

	@Test
	@DisplayName("확인: 발송이 저장한 해시를 그대로 대조한다 — 발송된 코드는 통과하고 다른 시크릿의 해시는 거부된다")
	void validateMatchesHashProducedBySendUsingTheSameSecret() {
		when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(passwordUser()));
		service.sendResetCode(EMAIL);

		ArgumentCaptor<PasswordResetVerification> savedCaptor = ArgumentCaptor
			.forClass(PasswordResetVerification.class);
		ArgumentCaptor<String> sentCodeCaptor = ArgumentCaptor.forClass(String.class);
		verify(passwordResetVerificationRepository).save(savedCaptor.capture());
		verify(emailSender).sendPasswordResetCode(eq(EMAIL), sentCodeCaptor.capture());

		PasswordResetVerification sent = savedCaptor.getValue();
		String sentCode = sentCodeCaptor.getValue();
		stubLatestVerification(sent);

		assertThat(service.validateAndConsumeCode(EMAIL, sentCode)).isNotNull();
		assertThat(sent.getCodeHash()).isNotEqualTo(hmac(OTHER_SECRET, sentCode));
	}

	private void assertVerificationFailed(String code) {
		assertThatThrownBy(() -> service.validateAndConsumeCode(EMAIL, code))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
	}

	private void stubLatestVerification(PasswordResetVerification verification) {
		when(passwordResetVerificationRepository.findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(EMAIL))
			.thenReturn(Optional.of(verification));
	}

	// 기준 시각 기준으로 아직 유효한(만료 4분 남은) 미소비 발송 행.
	private static PasswordResetVerification validVerification(String code) {
		return PasswordResetVerification.create(EMAIL, hmac(SECRET, code), NOW.plusMinutes(4), NOW.minusMinutes(1));
	}

	private void assertTooManyRequestsWithoutAnySideEffect() {
		assertThatThrownBy(() -> service.sendResetCode(EMAIL))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		// 429는 행을 남기지 않는다 — 남기면 하루 10회 제한이 사실상 영구 차단으로 변한다(D5).
		verify(passwordResetVerificationRepository, never()).save(any());
		verifyNoInteractions(emailSender);
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
	// 프로덕션과 같은 팩토리를 쓴다 — 자리표시자 값은 User만 안다.
	private static User socialOnlyUser() {
		return User.createOAuthOnly(EMAIL, "social-user", NOW.minusDays(10));
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
