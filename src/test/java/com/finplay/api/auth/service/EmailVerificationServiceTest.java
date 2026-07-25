// 인증번호 발송 서비스의 제한 판정·HMAC 저장(원문 미저장)·이전 코드 무효화를 검증하는 단위 테스트 (ADR-0003)
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.auth.domain.EmailVerification;
import com.finplay.api.auth.email.EmailSender;
import com.finplay.api.auth.repository.EmailVerificationRepository;
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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

	private static final String SECRET = "unit-test-hmac-secret";
	private static final String EMAIL = "user@finplay.com";
	// Clock.fixed로 고정한 기준 시각. 발송 제한 임계값(60초·1시간·하루)이 이 값 기준으로 계산된다.
	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-25T10:30:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	@Mock
	private UserRepository userRepository;

	@Mock
	private EmailVerificationRepository emailVerificationRepository;

	@Mock
	private EmailSender emailSender;

	private EmailVerificationService service;

	@BeforeEach
	void setUp() {
		Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		service = new EmailVerificationService(
			userRepository, emailVerificationRepository, emailSender, clock, SECRET);
	}

	@Test
	@DisplayName("정상 발송: 6자리 코드를 발송하고 저장된 code_hash는 원문이 아닌 실제 HMAC 결과다")
	void sendsCodeAndStoresHmacInsteadOfRawCode() {
		when(userRepository.existsByEmail(EMAIL)).thenReturn(false);

		service.sendVerificationCode(EMAIL);

		ArgumentCaptor<EmailVerification> savedCaptor = ArgumentCaptor.forClass(EmailVerification.class);
		ArgumentCaptor<String> sentCodeCaptor = ArgumentCaptor.forClass(String.class);
		verify(emailVerificationRepository).save(savedCaptor.capture());
		verify(emailSender).sendVerificationCode(eq(EMAIL), sentCodeCaptor.capture());

		String sentCode = sentCodeCaptor.getValue();
		EmailVerification saved = savedCaptor.getValue();

		// 발송된 코드는 6자리 숫자.
		assertThat(sentCode).matches("\\d{6}");
		// 저장된 해시는 원문 코드와 달라야 하고(원문 미저장), 실제 HMAC-SHA-256(hex 64자) 결과여야 한다.
		assertThat(saved.getCodeHash()).isNotEqualTo(sentCode);
		assertThat(saved.getCodeHash()).hasSize(64);
		assertThat(saved.getCodeHash()).isEqualTo(expectedHmac(sentCode));
		// TTL·시각이 고정 Clock 기준으로 설정된다.
		assertThat(saved.getExpiresAt()).isEqualTo(NOW.plusMinutes(5));
		assertThat(saved.getLastSentAt()).isEqualTo(NOW);
		assertThat(saved.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("기존 회원 이메일이면 저장·발송 없이 DUPLICATE_RESOURCE(409)를 던진다")
	void throwsDuplicateWhenEmailAlreadyRegistered() {
		when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

		assertThatThrownBy(() -> service.sendVerificationCode(EMAIL))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.DUPLICATE_RESOURCE);

		verify(emailVerificationRepository, never()).save(any());
		verify(emailSender, never()).sendVerificationCode(any(), any());
	}

	@Test
	@DisplayName("60초 이내 재요청이면 TOO_MANY_REQUESTS(429)를 던진다")
	void throwsTooManyRequestsWhenResentWithin60Seconds() {
		when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
		when(emailVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusSeconds(60)))
			.thenReturn(1L);

		assertThatThrownBy(() -> service.sendVerificationCode(EMAIL))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		verify(emailVerificationRepository, never()).save(any());
		verify(emailSender, never()).sendVerificationCode(any(), any());
	}

	@Test
	@DisplayName("최근 1시간 발송이 5회 이상이면 TOO_MANY_REQUESTS(429)를 던진다")
	void throwsTooManyRequestsWhenHourlyLimitReached() {
		when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
		// 60초 창은 0, 1시간 창이 한도(5)에 도달.
		when(emailVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusSeconds(60)))
			.thenReturn(0L);
		when(emailVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusHours(1)))
			.thenReturn(5L);

		assertThatThrownBy(() -> service.sendVerificationCode(EMAIL))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		verify(emailVerificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("최근 하루 발송이 10회 이상이면 TOO_MANY_REQUESTS(429)를 던진다")
	void throwsTooManyRequestsWhenDailyLimitReached() {
		when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
		// 60초·1시간 창은 0, 하루 창이 한도(10)에 도달.
		when(emailVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusSeconds(60)))
			.thenReturn(0L);
		when(emailVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusHours(1)))
			.thenReturn(0L);
		when(emailVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusDays(1)))
			.thenReturn(10L);

		assertThatThrownBy(() -> service.sendVerificationCode(EMAIL))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		verify(emailVerificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("각 창이 한도 바로 아래면(1시간 4회·하루 9회) 정상 발송된다 — 경계 통과")
	void sendsWhenCountsAreJustBelowLimits() {
		when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
		when(emailVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusSeconds(60)))
			.thenReturn(0L);
		when(emailVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusHours(1)))
			.thenReturn(4L);
		when(emailVerificationRepository.countByEmailAndCreatedAtAfter(EMAIL, NOW.minusDays(1)))
			.thenReturn(9L);

		service.sendVerificationCode(EMAIL);

		verify(emailVerificationRepository).save(any());
		verify(emailSender).sendVerificationCode(eq(EMAIL), any());
	}

	@Test
	@DisplayName("재발송 시 조회된 이전 미확인 행은 기준 시각으로 만료 처리된다")
	void expiresPreviousUnverifiedCodesOnResend() {
		when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
		EmailVerification previous = EmailVerification.create(EMAIL, "old-hash", NOW.plusMinutes(10),
			NOW.minusMinutes(2));
		when(emailVerificationRepository.findByEmailAndVerifiedAtIsNullAndExpiresAtAfter(EMAIL, NOW))
			.thenReturn(List.of(previous));

		// 만료 처리 전에는 아직 유효(만료 시각이 기준 시각 이후).
		assertThat(previous.getExpiresAt()).isEqualTo(NOW.plusMinutes(10));

		service.sendVerificationCode(EMAIL);

		// expire(now)가 호출되어 만료 시각이 기준 시각으로 당겨진다 = 즉시 무효화.
		assertThat(previous.getExpiresAt()).isEqualTo(NOW);
	}

	private static String expectedHmac(String code) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
