// 실제 MySQL로 비밀번호 재설정 발송의 원문 미저장·거부 행 커밋 후 429·발송 실패 동반 롤백·회원 자산 불변을 검증하는 통합 테스트다.
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.PasswordResetVerification;
import com.finplay.api.auth.domain.RefreshToken;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.email.FakeEmailSender;
import com.finplay.api.auth.oauth.OAuthProviderName;
import com.finplay.api.auth.oauth.OAuthUserDto;
import com.finplay.api.auth.repository.PasswordResetVerificationRepository;
import com.finplay.api.auth.repository.RefreshTokenRepository;
import com.finplay.api.auth.repository.SocialAccountRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PasswordResetIntegrationTest {

	// build.gradle의 test 태스크가 주입하는 값과 같아야 저장된 해시를 테스트가 재현할 수 있다.
	private static final String SECRET = "test-password-reset-secret-that-is-at-least-32-bytes";
	private static final String PASSWORD = "password123";

	@Autowired
	private PasswordResetService passwordResetService;

	@Autowired
	private PasswordResetVerificationRepository passwordResetVerificationRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private AuthService authService;

	@Autowired
	private SocialAccountRepository socialAccountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock clock;

	// 발송 실패 롤백을 재현하려면 예외를 던질 수 있어야 한다. 기본 동작은 실제 FakeEmailSender에 위임된다.
	@MockitoSpyBean
	private FakeEmailSender fakeEmailSender;

	@BeforeEach
	void clearSentEmails() {
		fakeEmailSender.clear();
	}

	@Test
	@DisplayName("발송에 성공하면 DB에는 인증번호 원문이 없고 전용 시크릿 기반 HMAC 해시만 남는다")
	void storesOnlyHmacHashAndNeverTheRawCode() {
		User user = persistEmailUser("reset-success");

		passwordResetService.sendResetCode(user.getEmail());

		String sentCode = fakeEmailSender.getLastSentEmail().code();
		assertThat(sentCode).matches("\\d{6}");

		PasswordResetVerification stored = onlyRowFor(user.getEmail());
		assertThat(stored.getCodeHash()).isEqualTo(hmac(sentCode));
		assertThat(stored.getCodeHash()).isNotEqualTo(sentCode);
		assertThat(stored.getExpiresAt()).isNotNull();
		assertThat(stored.getLastSentAt()).isNotNull();
		assertThat(stored.getConsumedAt()).isNull();

		// 컬럼 단위가 아니라 행 전체를 문자열로 훑어 원문이 어느 컬럼에도 새지 않았음을 확인한다.
		List<String> rowDumps = jdbcTemplate.queryForList(
			"select concat_ws('|', id, email, code_hash, attempt_count, expires_at, last_sent_at, consumed_at,"
				+ " created_at) from password_reset_verifications where email = ?",
			String.class, user.getEmail());
		assertThat(rowDumps).hasSize(1);
		assertThat(rowDumps.get(0)).doesNotContain(sentCode);
	}

	@Test
	@DisplayName("미가입 이메일의 404 거부 행은 예외 이후에도 커밋되어 곧바로 이어진 요청을 429로 막는다")
	void commitsRejectedRowForUnknownEmailAndBlocksFollowUpRequest() {
		String unknownEmail = uniqueEmail("reset-unknown");

		BusinessException notFound = catchThrowableOfType(
			BusinessException.class, () -> passwordResetService.sendResetCode(unknownEmail));
		assertThat(notFound.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);

		// noRollbackFor가 없으면 예외와 함께 이 행이 사라져 열거 시도를 세지 못한다.
		PasswordResetVerification rejected = onlyRowFor(unknownEmail);
		assertThat(rejected.getCodeHash()).isNull();
		assertThat(rejected.getExpiresAt()).isNull();
		assertThat(rejected.getLastSentAt()).isNull();

		BusinessException blocked = catchThrowableOfType(
			BusinessException.class, () -> passwordResetService.sendResetCode(unknownEmail));
		assertThat(blocked.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		// 429는 행을 남기지 않으므로 거부 행 하나만 그대로다.
		assertThat(passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(
			unknownEmail, LocalDateTime.now(clock).minusDays(1))).isEqualTo(1);
		assertThat(fakeEmailSender.getSentEmails()).isEmpty();
	}

	@Test
	@DisplayName("소셜 전용 회원의 409 거부 행도 커밋되어 곧바로 이어진 요청을 429로 막는다")
	void commitsRejectedRowForSocialOnlyAccountAndBlocksFollowUpRequest() {
		User socialOnly = persistSocialOnlyUser("reset-social");

		// 프로덕션 형태 확인 — 실제 OAuth 가입 경로로 만들어졌고(social_accounts 행 존재),
		// password_hash는 NULL이 아니지만 재설정할 비밀번호는 없다. 자리표시자 값 자체는 User만 안다.
		assertThat(socialAccountRepository.findByUserId(socialOnly.getId())).isPresent();
		assertThat(socialOnly.getPasswordHash()).isNotNull();
		assertThat(socialOnly.hasPassword()).isFalse();

		BusinessException conflict = catchThrowableOfType(
			BusinessException.class, () -> passwordResetService.sendResetCode(socialOnly.getEmail()));
		assertThat(conflict.getErrorCode()).isEqualTo(ErrorCode.SOCIAL_ACCOUNT_ONLY);

		PasswordResetVerification rejected = onlyRowFor(socialOnly.getEmail());
		assertThat(rejected.getCodeHash()).isNull();

		BusinessException blocked = catchThrowableOfType(
			BusinessException.class, () -> passwordResetService.sendResetCode(socialOnly.getEmail()));
		assertThat(blocked.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		assertThat(fakeEmailSender.getSentEmails()).isEmpty();
	}

	@Test
	@DisplayName("메일 발송이 실패하면 새 행 저장과 이전 코드 무효화가 함께 롤백된다")
	void rollsBackSavedRowAndPreviousCodeExpiryWhenSendingFails() {
		User user = persistEmailUser("reset-rollback");
		LocalDateTime now = LocalDateTime.now(clock);
		// 발송 제한(60초)에 걸리지 않도록 2분 전에 만들어진, 아직 유효한 이전 코드를 심는다.
		PasswordResetVerification previous = passwordResetVerificationRepository.saveAndFlush(
			PasswordResetVerification.create(user.getEmail(), hmac("111111"), now.plusMinutes(3), now.minusMinutes(2)));
		LocalDateTime previousExpiresAt = previous.getExpiresAt();

		doThrow(new IllegalStateException("메일 발송 실패"))
			.when(fakeEmailSender).sendPasswordResetCode(any(), any());

		assertThat(catchThrowableOfType(IllegalStateException.class,
			() -> passwordResetService.sendResetCode(user.getEmail())))
			.hasMessage("메일 발송 실패");

		// 새 행이 커밋되지 않았어야 한다 — 이전 행 하나만 남는다.
		List<PasswordResetVerification> rows = rowsFor(user.getEmail());
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getId()).isEqualTo(previous.getId());
		// 이전 코드 무효화도 함께 되돌아가 만료 시각이 그대로여야 한다.
		// DATETIME(6)은 마이크로초로 반올림되므로 1ms 허용 오차로 비교한다 — 무효화되면 2분 이상 당겨지므로 검증력은 유지된다.
		assertThat(rows.get(0).getExpiresAt()).isCloseTo(previousExpiresAt, within(1, ChronoUnit.MILLIS));
		assertThat(rows.get(0).getCodeHash()).isEqualTo(hmac("111111"));
	}

	@Test
	@DisplayName("재발송에 성공하면 이전 코드가 커밋된 채로 무효화되어 유효한 코드가 최대 1개다")
	void expiresPreviousCodeOnSuccessfulResend() {
		User user = persistEmailUser("reset-resend");
		LocalDateTime now = LocalDateTime.now(clock);
		PasswordResetVerification previous = passwordResetVerificationRepository.saveAndFlush(
			PasswordResetVerification.create(user.getEmail(), hmac("111111"), now.plusMinutes(3), now.minusMinutes(2)));

		passwordResetService.sendResetCode(user.getEmail());

		List<PasswordResetVerification> stillValid = passwordResetVerificationRepository
			.findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(
				user.getEmail(), LocalDateTime.now(clock));
		assertThat(stillValid).hasSize(1);
		assertThat(stillValid.get(0).getId()).isNotEqualTo(previous.getId());
		assertThat(stillValid.get(0).getCodeHash()).isEqualTo(hmac(fakeEmailSender.getLastSentEmail().code()));
	}

	@Test
	@DisplayName("성공·404·409·발송실패 전 시나리오에서 users·accounts·refresh_tokens가 한 행도 바뀌지 않는다")
	void neverTouchesUsersAccountsOrRefreshTokens() {
		User user = persistEmailUser("reset-invariant");
		LocalDateTime now = LocalDateTime.now(clock);
		RefreshToken refreshToken = refreshTokenRepository.saveAndFlush(
			RefreshToken.create(user, "refresh-token-hash-" + UUID.randomUUID(), now.plusDays(14), now));
		String storedPasswordHash = user.getPasswordHash();

		// 시나리오에 필요한 회원은 스냅샷 이전에 모두 만들어 둔다 — 이후 증감은 전부 재설정 발송 탓이어야 한다.
		User socialOnly = persistSocialOnlyUser("reset-invariant-social");
		User failing = persistEmailUser("reset-invariant-failing");

		long userCount = userRepository.count();
		long accountCount = countAccountsOf(user.getId());
		long refreshTokenCount = refreshTokenRepository.count();

		// 성공 경로.
		passwordResetService.sendResetCode(user.getEmail());
		// 404 경로.
		catchThrowableOfType(BusinessException.class,
			() -> passwordResetService.sendResetCode(uniqueEmail("reset-invariant-unknown")));
		// 409 경로.
		catchThrowableOfType(BusinessException.class,
			() -> passwordResetService.sendResetCode(socialOnly.getEmail()));
		// 발송 실패 경로 — 60초 제한을 피하려고 아직 요청이 없는 다른 회원을 쓴다.
		doThrow(new IllegalStateException("메일 발송 실패"))
			.when(fakeEmailSender).sendPasswordResetCode(any(), any());
		catchThrowableOfType(IllegalStateException.class,
			() -> passwordResetService.sendResetCode(failing.getEmail()));

		// 네 경로 어디서도 회원·계좌·Refresh Token은 한 행도 늘거나 줄지 않는다.
		assertThat(userRepository.count()).isEqualTo(userCount);
		assertThat(countAccountsOf(user.getId())).isEqualTo(accountCount);
		assertThat(refreshTokenRepository.count()).isEqualTo(refreshTokenCount);

		User reloaded = userRepository.findById(user.getId()).orElseThrow();
		assertThat(reloaded.getPasswordHash()).isEqualTo(storedPasswordHash);
		assertThat(reloaded.getEmail()).isEqualTo(user.getEmail());

		RefreshToken reloadedToken = refreshTokenRepository.findById(refreshToken.getId()).orElseThrow();
		assertThat(reloadedToken.getRevokedAt()).isNull();
		assertThat(reloadedToken.getExpiresAt())
			.isCloseTo(refreshToken.getExpiresAt(), within(1, ChronoUnit.MILLIS));
	}

	@Test
	@DisplayName("발송 성공 시 users의 password_hash는 그대로다 — 이번 이슈는 발송까지만 한다")
	void doesNotReplacePasswordHashOnSend() {
		User user = persistEmailUser("reset-password-untouched");
		String storedPasswordHash = user.getPasswordHash();

		passwordResetService.sendResetCode(user.getEmail());

		assertThat(userRepository.findById(user.getId()).orElseThrow().getPasswordHash())
			.isEqualTo(storedPasswordHash);
	}

	private PasswordResetVerification onlyRowFor(String email) {
		List<PasswordResetVerification> rows = rowsFor(email);
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private List<PasswordResetVerification> rowsFor(String email) {
		List<Long> ids = jdbcTemplate.queryForList(
			"select id from password_reset_verifications where email = ? order by id", Long.class, email);
		return ids.stream()
			.map(id -> passwordResetVerificationRepository.findById(id).orElseThrow())
			.toList();
	}

	private long countAccountsOf(Long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from accounts where user_id = ?", Long.class, userId);
	}

	private User persistEmailUser(String scenario) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.saveAndFlush(User.create(
			uniqueEmail(scenario), passwordEncoder.encode(PASSWORD), uniqueNickname(scenario), now));
		accountService.createAccountsFor(user);
		return user;
	}

	// 소셜 전용 회원은 손으로 만들지 않고 실제 OAuth 가입 경로를 태운다.
	// 직접 User.create(email, null, ...)로 만들면 프로덕션에 없는 형태(password_hash NULL)가 되어
	// 409 분기가 도달 불가여도 테스트가 통과한다 — 실제로 그렇게 결함을 놓친 적이 있다.
	private User persistSocialOnlyUser(String scenario) {
		OAuthUserDto oauthUser = new OAuthUserDto(
			"provider-" + scenario + "-" + UUID.randomUUID().toString().replace("-", ""),
			uniqueEmail(scenario));
		authService.oauthLogin(OAuthProviderName.KAKAO, oauthUser);
		return userRepository.findByEmail(oauthUser.email()).orElseThrow();
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	private static String hmac(String code) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
