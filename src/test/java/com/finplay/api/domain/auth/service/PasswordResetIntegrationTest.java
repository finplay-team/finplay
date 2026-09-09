package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.email.FakeEmailSender;
import com.finplay.api.domain.auth.entity.PasswordResetVerification;
import com.finplay.api.domain.auth.entity.RefreshToken;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
import com.finplay.api.domain.auth.repository.PasswordResetVerificationRepository;
import com.finplay.api.domain.auth.repository.RefreshTokenRepository;
import com.finplay.api.domain.auth.repository.SocialAccountRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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

		PasswordResetVerification rejected = onlyRowFor(unknownEmail);
		assertThat(rejected.getCodeHash()).isNull();
		assertThat(rejected.getExpiresAt()).isNull();
		assertThat(rejected.getLastSentAt()).isNull();

		BusinessException blocked = catchThrowableOfType(
			BusinessException.class, () -> passwordResetService.sendResetCode(unknownEmail));
		assertThat(blocked.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		assertThat(passwordResetVerificationRepository.countByEmailAndCreatedAtAfter(
			unknownEmail, LocalDateTime.now(clock).minusDays(1))).isEqualTo(1);
		assertThat(fakeEmailSender.getSentEmails()).isEmpty();
	}

	@Test
	@DisplayName("소셜 전용 회원의 409 거부 행도 커밋되어 곧바로 이어진 요청을 429로 막는다")
	void commitsRejectedRowForSocialOnlyAccountAndBlocksFollowUpRequest() {
		User socialOnly = persistSocialOnlyUser("reset-social");

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
		PasswordResetVerification previous = passwordResetVerificationRepository.saveAndFlush(
			PasswordResetVerification.create(user.getEmail(), hmac("111111"), now.plusMinutes(3), now.minusMinutes(2)));
		LocalDateTime previousExpiresAt = previous.getExpiresAt();

		doThrow(new IllegalStateException("메일 발송 실패"))
			.when(fakeEmailSender).sendPasswordResetCode(any(), any());

		assertThat(catchThrowableOfType(IllegalStateException.class,
			() -> passwordResetService.sendResetCode(user.getEmail())))
			.hasMessage("메일 발송 실패");

		List<PasswordResetVerification> rows = rowsFor(user.getEmail());
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).getId()).isEqualTo(previous.getId());
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

		User socialOnly = persistSocialOnlyUser("reset-invariant-social");
		User failing = persistEmailUser("reset-invariant-failing");

		long userCount = userRepository.count();
		long accountCount = countAccountsOf(user.getId());
		long refreshTokenCount = refreshTokenRepository.count();

		passwordResetService.sendResetCode(user.getEmail());
		catchThrowableOfType(BusinessException.class,
			() -> passwordResetService.sendResetCode(uniqueEmail("reset-invariant-unknown")));
		catchThrowableOfType(BusinessException.class,
			() -> passwordResetService.sendResetCode(socialOnly.getEmail()));
		doThrow(new IllegalStateException("메일 발송 실패"))
			.when(fakeEmailSender).sendPasswordResetCode(any(), any());
		catchThrowableOfType(IllegalStateException.class,
			() -> passwordResetService.sendResetCode(failing.getEmail()));

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
