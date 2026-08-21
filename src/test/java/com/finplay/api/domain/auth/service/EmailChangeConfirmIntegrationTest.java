// 실제 MySQL로 이메일 변경 확인의 성공·재사용·5회초과·만료·재발송무효화·타인요청격리·동시경합 롤백·Refresh Token 폐기를 검증하는 통합 테스트다.
package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.EmailChangeVerification;
import com.finplay.api.domain.auth.entity.ReauthToken;
import com.finplay.api.domain.auth.entity.RefreshToken;
import com.finplay.api.domain.auth.entity.SignupMethod;
import com.finplay.api.domain.auth.entity.SocialAccount;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.dto.response.MemberResponse;
import com.finplay.api.domain.auth.dto.response.TokenResponse;
import com.finplay.api.domain.auth.email.FakeEmailSender;
import com.finplay.api.domain.auth.exception.EmailChangeConflictException;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.repository.EmailChangeVerificationRepository;
import com.finplay.api.domain.auth.repository.ReauthTokenRepository;
import com.finplay.api.domain.auth.repository.RefreshTokenRepository;
import com.finplay.api.domain.auth.repository.SocialAccountRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EmailChangeConfirmIntegrationTest {

	private static final String PASSWORD = "password123";

	@Autowired
	private AuthService authService;

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
	private RefreshTokenRepository refreshTokenRepository;

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
	void confirmEmailChangeSucceedsAndSameCodeReuseFailsAfterward() {
		User user = persistEmailUser("confirm-success");
		String newEmail = uniqueEmail("confirm-success-new");
		String code = sendCode(user.getId(), newEmail);

		MemberResponse response = authService.confirmEmailChange(user.getId(), newEmail, code);

		assertThat(response.id()).isEqualTo(user.getId());
		assertThat(response.email()).isEqualTo(newEmail);
		assertThat(response.signupMethod()).isEqualTo(SignupMethod.EMAIL);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(newEmail);

		BusinessException reuseFailure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.confirmEmailChange(user.getId(), newEmail, code));

		assertThat(reuseFailure.getErrorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(newEmail);
	}

	@Test
	void confirmEmailChangeFailsWhenNoPriorRequestExists() {
		User user = persistEmailUser("confirm-no-request");
		String newEmail = uniqueEmail("confirm-no-request-target");

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.confirmEmailChange(user.getId(), newEmail, "123456"));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(user.getEmail());
	}

	@Test
	void confirmEmailChangeFailsWithWrongCodeAndIncrementsAttemptCount() {
		User user = persistEmailUser("confirm-wrong-code");
		String newEmail = uniqueEmail("confirm-wrong-code-target");
		String code = sendCode(user.getId(), newEmail);
		String wrongCode = wrongCodeFor(code);

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.confirmEmailChange(user.getId(), newEmail, wrongCode));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(user.getEmail());
		EmailChangeVerification verification = latestRow(user.getId(), newEmail);
		assertThat(verification.getAttemptCount()).isEqualTo(1);
		assertThat(verification.getConsumedAt()).isNull();
	}

	@Test
	void confirmEmailChangeFailsAfterExpirationAndLeavesEmailUnchanged() {
		User user = persistEmailUser("confirm-expired");
		String newEmail = uniqueEmail("confirm-expired-target");
		String code = sendCode(user.getId(), newEmail);
		EmailChangeVerification stored = latestRow(user.getId(), newEmail);
		jdbcTemplate.update(
			"update email_change_verifications set expires_at = ? where id = ?",
			LocalDateTime.now(clock).minusSeconds(1),
			stored.getId());

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.confirmEmailChange(user.getId(), newEmail, code));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(user.getEmail());
	}

	@Test
	void confirmEmailChangeFailsWithCodeInvalidatedByResend() {
		User user = persistEmailUser("confirm-resend-invalidated");
		String newEmail = uniqueEmail("confirm-resend-invalidated-target");
		String firstCode = sendCode(user.getId(), newEmail);
		EmailChangeVerification firstRow = latestRow(user.getId(), newEmail);
		// 60초 재발송 간격 판정은 created_at 기준이므로, 실제 대기 대신 created_at을 뒤로 당겨 창을 지난 것처럼 만든다.
		jdbcTemplate.update(
			"update email_change_verifications set created_at = ? where id = ?",
			LocalDateTime.now(clock).minusSeconds(61),
			firstRow.getId());
		sendCode(user.getId(), newEmail);

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.confirmEmailChange(user.getId(), newEmail, firstCode));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(user.getEmail());
		EmailChangeVerification latest = latestRow(user.getId(), newEmail);
		assertThat(latest.getId()).isNotEqualTo(firstRow.getId());
		assertThat(latest.getConsumedAt()).isNull();
	}

	@Test
	void confirmEmailChangeFailsForOtherUsersRequestAndLeavesBothUnchanged() {
		User owner = persistEmailUser("confirm-isolation-owner");
		User attacker = persistEmailUser("confirm-isolation-attacker");
		String newEmail = uniqueEmail("confirm-isolation-target");
		String code = sendCode(owner.getId(), newEmail);

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.confirmEmailChange(attacker.getId(), newEmail, code));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
		assertThat(userRepository.findById(owner.getId()).orElseThrow().getEmail()).isEqualTo(owner.getEmail());
		assertThat(userRepository.findById(attacker.getId()).orElseThrow().getEmail()).isEqualTo(attacker.getEmail());

		// 타인의 실패한 시도가 소유자의 인증번호를 소비하지 않았음을 증명 — 소유자는 여전히 같은 코드로 성공해야 한다.
		MemberResponse response = authService.confirmEmailChange(owner.getId(), newEmail, code);
		assertThat(response.email()).isEqualTo(newEmail);
	}

	@Test
	void confirmEmailChangeExceedingMaxAttemptsReturnsTooManyRequestsAndInvalidatesCodeAfterward() {
		User user = persistEmailUser("confirm-max-attempts");
		String newEmail = uniqueEmail("confirm-max-attempts-target");
		String code = sendCode(user.getId(), newEmail);
		String wrongCode = wrongCodeFor(code);

		for (int attempt = 1; attempt <= 5; attempt++) {
			BusinessException failure = catchThrowableOfType(
				BusinessException.class,
				() -> authService.confirmEmailChange(user.getId(), newEmail, wrongCode));
			assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
		}

		BusinessException sixthFailure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.confirmEmailChange(user.getId(), newEmail, code));
		assertThat(sixthFailure.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);

		EmailChangeVerification verification = latestRow(user.getId(), newEmail);
		assertThat(verification.getAttemptCount()).isEqualTo(6);
		assertThat(verification.getExpiresAt()).isBeforeOrEqualTo(LocalDateTime.now(clock));

		BusinessException afterExpiry = catchThrowableOfType(
			BusinessException.class,
			() -> authService.confirmEmailChange(user.getId(), newEmail, code));
		assertThat(afterExpiry.getErrorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getEmail()).isEqualTo(user.getEmail());
	}

	// D2의 가장 중요한 검증 대상: EmailChangeConflictException(depth 0)이 BusinessException에 대한
	// noRollbackFor(depth 1)보다 우선 매칭되어 인증번호 소비·Refresh Token 폐기가 함께 롤백되는지 확인한다.
	@Test
	void confirmEmailChangeConflictWithAlreadyTakenEmailRollsBackConsumptionAndTokenRevocationAtomically() {
		// 두 회원 모두 아직 아무도 확정하지 않은 같은 새 이메일로 각자 유효한 인증번호를 발급받는다 — 발송 단계는 둘 다 성공해야 한다.
		User winner = persistEmailUser("confirm-conflict-winner");
		User loser = persistEmailUser("confirm-conflict-loser");
		String targetEmail = uniqueEmail("confirm-conflict-target");
		String winnerCode = sendCode(winner.getId(), targetEmail);
		String loserCode = sendCode(loser.getId(), targetEmail);
		TokenResponse loserLoginTokens = authService.login(loser.getEmail(), PASSWORD);

		// 승자가 먼저 확인에 성공해 targetEmail을 선점한다.
		authService.confirmEmailChange(winner.getId(), targetEmail, winnerCode);

		// 패자는 자신의 유효한 인증번호로 확인하지만, saveAndFlush 시점에 유니크 제약과 충돌해 409로 전체 롤백돼야 한다.
		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.confirmEmailChange(loser.getId(), targetEmail, loserCode));

		assertThat(failure).isInstanceOf(EmailChangeConflictException.class);
		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);

		User reloadedLoser = runInNewTransaction(() -> userRepository.findById(loser.getId()).orElseThrow());
		assertThat(reloadedLoser.getEmail()).isEqualTo(loser.getEmail());
		assertThat(reloadedLoser.getEmail()).isNotEqualTo(targetEmail);

		EmailChangeVerification verification = latestRow(loser.getId(), targetEmail);
		assertThat(verification.getConsumedAt()).isNull();

		List<RefreshToken> tokens = runInNewTransaction(
			() -> refreshTokenRepository.findAllByTokenHash(sha256(loserLoginTokens.refreshToken())));
		assertThat(tokens).singleElement().extracting(RefreshToken::getRevokedAt).isNull();
	}

	@Test
	void confirmEmailChangeSuccessRevokesExistingRefreshTokenSoOldTokenFailsRefreshAfterward() {
		User user = persistEmailUser("confirm-refresh-revoke");
		TokenResponse loginTokens = authService.login(user.getEmail(), PASSWORD);
		String newEmail = uniqueEmail("confirm-refresh-revoke-target");
		String code = sendCode(user.getId(), newEmail);

		authService.confirmEmailChange(user.getId(), newEmail, code);

		BusinessException refreshFailure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.refresh(loginTokens.refreshToken()));
		assertThat(refreshFailure.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
	}

	@Test
	void confirmEmailChangeSuccessAndFailureLeaveAccountsUnchanged() {
		User user = persistEmailUser("confirm-invariance");
		List<AccountSnapshot> accountsBefore = snapshotAccounts(user.getId());
		assertThat(accountsBefore)
			.hasSize(2)
			.extracting(AccountSnapshot::market)
			.containsExactlyInAnyOrder(Market.STOCK, Market.CRYPTO);
		String newEmail = uniqueEmail("confirm-invariance-target");
		String code = sendCode(user.getId(), newEmail);

		authService.confirmEmailChange(user.getId(), newEmail, code);
		assertThat(snapshotAccounts(user.getId())).isEqualTo(accountsBefore);

		// 재사용 실패(확인 실패) 경로도 계좌를 건드리지 않는다.
		catchThrowableOfType(
			BusinessException.class,
			() -> authService.confirmEmailChange(user.getId(), newEmail, code));
		assertThat(snapshotAccounts(user.getId())).isEqualTo(accountsBefore);
	}

	@Test
	void confirmEmailChangeForOAuthUserKeepsSocialAccountLinkUnchanged() {
		User user = persistOAuthUser("confirm-oauth-link");
		SocialAccount linkBefore = socialAccountRepository.findByUserId(user.getId()).orElseThrow();
		String rawReauthToken = UUID.randomUUID().toString();
		LocalDateTime now = LocalDateTime.now(clock);
		reauthTokenRepository.saveAndFlush(
			ReauthToken.create(user, sha256(rawReauthToken), now.plusMinutes(5), now));
		String newEmail = uniqueEmail("confirm-oauth-link-target");
		emailChangeService.requestEmailChange(user.getId(), newEmail, null, rawReauthToken);
		FakeEmailSender.SentEmail sentEmail = fakeEmailSender.getLastSentEmail();
		assertThat(sentEmail).isNotNull();

		MemberResponse response = authService.confirmEmailChange(user.getId(), newEmail, sentEmail.code());

		assertThat(response.email()).isEqualTo(newEmail);
		assertThat(response.signupMethod()).isEqualTo(SignupMethod.KAKAO);
		SocialAccount linkAfter = socialAccountRepository.findByUserId(user.getId()).orElseThrow();
		assertThat(linkAfter.getProvider()).isEqualTo(linkBefore.getProvider());
		assertThat(linkAfter.getProviderUserId()).isEqualTo(linkBefore.getProviderUserId());
	}

	private String sendCode(Long userId, String newEmail) {
		emailChangeService.requestEmailChange(userId, newEmail, PASSWORD, null);
		FakeEmailSender.SentEmail sentEmail = fakeEmailSender.getLastSentEmail();
		assertThat(sentEmail).isNotNull();
		assertThat(sentEmail.toEmail()).isEqualTo(newEmail);
		return sentEmail.code();
	}

	// 확인 대상 최신 행을 상태 단정용으로 읽는다. 확인 경로의 조회 쿼리
	// (findFirstByUserIdAndNewEmailOrderByCreatedAtDesc)는 @Lock(PESSIMISTIC_WRITE)이라 트랜잭션 밖에서 부르면
	// MySQL이 read-only 트랜잭션의 SELECT ... FOR UPDATE를 거부한다. 그래서 id는 jdbcTemplate으로 집고
	// 잠금 없는 findById로 읽는다 (PR #120의 PasswordResetConfirmIntegrationTest와 같은 방식).
	private EmailChangeVerification latestRow(Long userId, String newEmail) {
		Long id = jdbcTemplate.queryForObject(
			"select id from email_change_verifications"
				+ " where user_id = ? and new_email = ? order by created_at desc, id desc limit 1",
			Long.class, userId, newEmail);
		return emailChangeVerificationRepository.findById(id).orElseThrow();
	}

	private <T> T runInNewTransaction(Supplier<T> action) {
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return transactionTemplate.execute(status -> action.get());
	}

	private List<AccountSnapshot> snapshotAccounts(Long userId) {
		return accountRepository.findAllByUserId(userId).stream()
			.map(AccountSnapshot::from)
			.sorted(Comparator.comparing(AccountSnapshot::market))
			.toList();
	}

	private User persistEmailUser(String scenario) {
		return persistEmailUserWithEmail(scenario, uniqueEmail(scenario));
	}

	private User persistEmailUserWithEmail(String scenario, String email) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.saveAndFlush(User.create(
			email, passwordEncoder.encode(PASSWORD), uniqueNickname(scenario), now));
		accountService.createAccountsFor(user);
		return user;
	}

	private User persistOAuthUser(String scenario) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.saveAndFlush(User.create(
			uniqueEmail(scenario), passwordEncoder.encode(PASSWORD), uniqueNickname(scenario), now));
		accountService.createAccountsFor(user);
		socialAccountRepository.saveAndFlush(SocialAccount.create(
			user, OAuthProviderName.KAKAO, "oauth-user-" + UUID.randomUUID(), now));
		return user;
	}

	private static String wrongCodeFor(String code) {
		return code.equals("000000") ? "111111" : "000000";
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

	private record AccountSnapshot(
		Long id,
		Market market,
		long cashBalance,
		long seedMoney,
		long realizedPnl,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {

		private static AccountSnapshot from(Account account) {
			return new AccountSnapshot(
				account.getId(),
				account.getMarket(),
				account.getCashBalance(),
				account.getSeedMoney(),
				account.getRealizedPnl(),
				account.getCreatedAt(),
				account.getUpdatedAt());
		}
	}
}
