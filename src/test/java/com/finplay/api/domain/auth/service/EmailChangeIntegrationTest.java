// Fake EmailSender와 Testcontainers MySQL로 이메일 변경 인증번호 발송의 성공·실패 흐름과 기존 데이터 불변을 검증하는 통합 테스트다.
package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.ReauthToken;
import com.finplay.api.domain.auth.entity.SocialAccount;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.email.FakeEmailSender;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.repository.EmailChangeVerificationRepository;
import com.finplay.api.domain.auth.repository.ReauthTokenRepository;
import com.finplay.api.domain.auth.repository.SocialAccountRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EmailChangeIntegrationTest {

	private static final String PASSWORD = "password123";
	private static final String PROVIDER_USER_ID_PREFIX = "oauth-user-";

	@Autowired
	private EmailChangeService emailChangeService;

	@Autowired
	private FakeEmailSender fakeEmailSender;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private SocialAccountRepository socialAccountRepository;

	@Autowired
	private ReauthTokenRepository reauthTokenRepository;

	@Autowired
	private EmailChangeVerificationRepository emailChangeVerificationRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private Clock clock;

	@BeforeEach
	void clearSentEmails() {
		fakeEmailSender.clear();
	}

	@Test
	void emailMemberSendsCodeWithCorrectPasswordAndStoresHashOnlyWithoutChangingEmail() {
		User user = persistEmailUser("email-success");
		String newEmail = uniqueEmail("new-email-success");

		emailChangeService.requestEmailChange(user.getId(), newEmail, PASSWORD, null);

		FakeEmailSender.SentEmail sentEmail = fakeEmailSender.getLastSentEmail();
		assertThat(sentEmail).isNotNull();
		assertThat(sentEmail.toEmail()).isEqualTo(newEmail);
		assertThat(sentEmail.code()).matches("\\d{6}");

		var stored = emailChangeVerificationRepository.findAll().stream()
			.filter(verification -> verification.getUser().getId().equals(user.getId()))
			.toList();
		assertThat(stored).hasSize(1);
		assertThat(stored.get(0).getNewEmail()).isEqualTo(newEmail);
		assertThat(stored.get(0).getCodeHash())
			.isNotBlank()
			.isNotEqualTo(sentEmail.code());

		User reloadedUser = userRepository.findById(user.getId()).orElseThrow();
		assertThat(reloadedUser.getEmail()).isEqualTo(user.getEmail());
	}

	@Test
	void oauthMemberSendsCodeWithValidReauthTokenAndConsumesTokenWithoutChangingEmail() {
		User user = persistOAuthUser("oauth-success");
		String rawReauthToken = UUID.randomUUID().toString();
		LocalDateTime now = LocalDateTime.now(clock);
		ReauthToken reauthToken = reauthTokenRepository.saveAndFlush(
			ReauthToken.create(user, sha256(rawReauthToken), now.plusMinutes(5), now));
		String newEmail = uniqueEmail("new-oauth-success");

		emailChangeService.requestEmailChange(user.getId(), newEmail, null, rawReauthToken);

		FakeEmailSender.SentEmail sentEmail = fakeEmailSender.getLastSentEmail();
		assertThat(sentEmail).isNotNull();
		assertThat(sentEmail.toEmail()).isEqualTo(newEmail);

		ReauthToken reloadedReauthToken = reauthTokenRepository.findById(reauthToken.getId()).orElseThrow();
		assertThat(reloadedReauthToken.getConsumedAt()).isNotNull();

		User reloadedUser = userRepository.findById(user.getId()).orElseThrow();
		assertThat(reloadedUser.getEmail()).isEqualTo(user.getEmail());
	}

	@Test
	void wrongCurrentPasswordFailsWithReauthenticationFailedAndLeavesExistingDataUnchanged() {
		User user = persistEmailUser("email-wrong-password");
		String newEmail = uniqueEmail("new-email-wrong-password");
		Snapshot before = captureSnapshot(user.getId());

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> emailChangeService.requestEmailChange(user.getId(), newEmail, "wrong-password", null));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);
		assertThat(fakeEmailSender.getLastSentEmail()).isNull();
		assertSnapshotUnchanged(user.getId(), before);
	}

	@Test
	void alreadyConsumedReauthTokenFailsWithReauthenticationFailedAndLeavesExistingDataUnchanged() {
		User user = persistOAuthUser("oauth-consumed");
		String rawReauthToken = UUID.randomUUID().toString();
		LocalDateTime now = LocalDateTime.now(clock);
		reauthTokenRepository.saveAndFlush(
			ReauthToken.create(user, sha256(rawReauthToken), now.plusMinutes(5), now));
		// 사전에 한 번 소비해 "이미 소비된 토큰" 상태를 만든다. @Modifying 쿼리는 트랜잭션이 필요해 TransactionTemplate으로 감싼다.
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		int firstConsume = transactionTemplate.execute(status -> reauthTokenRepository
			.consumeIfValidForUser(sha256(rawReauthToken), user.getId(), now));
		assertThat(firstConsume).isEqualTo(1);
		String newEmail = uniqueEmail("new-oauth-consumed");
		Snapshot before = captureSnapshot(user.getId());

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> emailChangeService.requestEmailChange(user.getId(), newEmail, null, rawReauthToken));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);
		assertThat(fakeEmailSender.getLastSentEmail()).isNull();
		assertSnapshotUnchanged(user.getId(), before);
	}

	@Test
	void duplicateNewEmailFailsWithDuplicateResourceAndLeavesExistingDataUnchanged() {
		User owner = persistEmailUser("owner-of-target-email");
		User requester = persistEmailUser("requester-duplicate");
		Snapshot before = captureSnapshot(requester.getId());

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> emailChangeService.requestEmailChange(
				requester.getId(), owner.getEmail(), PASSWORD, null));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
		assertThat(fakeEmailSender.getLastSentEmail()).isNull();
		assertSnapshotUnchanged(requester.getId(), before);
	}

	@Test
	void resendWithin60SecondsFailsWithTooManyRequestsAndKeepsSingleStoredRow() {
		User user = persistEmailUser("email-rate-limit");
		String firstNewEmail = uniqueEmail("new-email-rate-limit-1");
		String secondNewEmail = uniqueEmail("new-email-rate-limit-2");

		emailChangeService.requestEmailChange(user.getId(), firstNewEmail, PASSWORD, null);
		var storedAfterFirst = findByUser(user.getId());
		assertThat(storedAfterFirst).hasSize(1);
		String firstCodeHash = storedAfterFirst.get(0).getCodeHash();

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> emailChangeService.requestEmailChange(user.getId(), secondNewEmail, PASSWORD, null));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
		var storedAfterSecondAttempt = findByUser(user.getId());
		assertThat(storedAfterSecondAttempt).hasSize(1);
		assertThat(storedAfterSecondAttempt.get(0).getCodeHash()).isEqualTo(firstCodeHash);
	}

	@Test
	void resendAfterRateLimitWindowForSameNewEmailSucceedsAndInvalidatesPreviousCode() {
		User user = persistEmailUser("email-resend-after-window");
		String newEmail = uniqueEmail("new-email-resend-after-window");

		emailChangeService.requestEmailChange(user.getId(), newEmail, PASSWORD, null);
		var firstRow = findByUser(user.getId()).get(0);
		// 60초 재발송 간격 판정은 created_at 기준이므로, 실제 대기 대신 저장된 created_at을 뒤로 당겨 창을 지난 것처럼 만든다.
		jdbcTemplate.update(
			"update email_change_verifications set created_at = ? where id = ?",
			LocalDateTime.now(clock).minusSeconds(61),
			firstRow.getId());

		emailChangeService.requestEmailChange(user.getId(), newEmail, PASSWORD, null);

		var storedAfterResend = findByUser(user.getId());
		assertThat(storedAfterResend).hasSize(2);
		var reloadedFirstRow = storedAfterResend.stream()
			.filter(verification -> verification.getId().equals(firstRow.getId()))
			.findFirst()
			.orElseThrow();
		var secondRow = storedAfterResend.stream()
			.filter(verification -> !verification.getId().equals(firstRow.getId()))
			.findFirst()
			.orElseThrow();

		assertThat(reloadedFirstRow.getExpiresAt()).isBeforeOrEqualTo(LocalDateTime.now(clock));
		assertThat(secondRow.getExpiresAt()).isAfter(LocalDateTime.now(clock));
		assertThat(secondRow.getCodeHash()).isNotEqualTo(reloadedFirstRow.getCodeHash());
	}

	private List<com.finplay.api.domain.auth.entity.EmailChangeVerification> findByUser(Long userId) {
		return emailChangeVerificationRepository.findAll().stream()
			.filter(verification -> verification.getUser().getId().equals(userId))
			.toList();
	}

	private User persistEmailUser(String scenario) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.saveAndFlush(User.create(
			uniqueEmail(scenario), passwordEncoder.encode(PASSWORD), uniqueNickname(scenario), now));
		accountService.createAccountsFor(user);
		return user;
	}

	private User persistOAuthUser(String scenario) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.saveAndFlush(User.create(
			uniqueEmail(scenario), passwordEncoder.encode(PASSWORD), uniqueNickname(scenario), now));
		accountService.createAccountsFor(user);
		socialAccountRepository.saveAndFlush(SocialAccount.create(
			user, OAuthProviderName.KAKAO, PROVIDER_USER_ID_PREFIX + UUID.randomUUID(), now));
		return user;
	}

	private Snapshot captureSnapshot(Long userId) {
		User user = userRepository.findById(userId).orElseThrow();
		List<Account> accounts = accountRepository.findAllByUserId(userId);
		return new Snapshot(user.getEmail(), List.copyOf(accounts), emailChangeVerificationRepository.count());
	}

	private void assertSnapshotUnchanged(Long userId, Snapshot before) {
		User reloadedUser = userRepository.findById(userId).orElseThrow();
		assertThat(reloadedUser.getEmail()).isEqualTo(before.email());
		assertThat(accountRepository.findAllByUserId(userId))
			.usingRecursiveFieldByFieldElementComparator()
			.containsExactlyInAnyOrderElementsOf(before.accounts());
		assertThat(emailChangeVerificationRepository.count()).isEqualTo(before.emailChangeVerificationCount());
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private record Snapshot(String email, List<Account> accounts, long emailChangeVerificationCount) {
	}
}
