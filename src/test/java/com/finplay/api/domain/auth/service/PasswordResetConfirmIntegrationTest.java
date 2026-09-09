package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.email.FakeEmailSender;
import com.finplay.api.domain.auth.entity.PasswordResetVerification;
import com.finplay.api.domain.auth.entity.SocialAccount;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.repository.PasswordResetVerificationRepository;
import com.finplay.api.domain.auth.repository.SocialAccountRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Market;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PasswordResetConfirmIntegrationTest {

	private static final String SEND_PATH = "/api/auth/password-resets";
	private static final String CONFIRM_PATH = "/api/auth/password-resets/confirm";
	private static final String LOGIN_PATH = "/api/auth/login";
	private static final String REFRESH_PATH = "/api/auth/refresh";
	private static final String PASSWORD = "password123";
	private static final String NEW_PASSWORD = "newSecret456";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private FakeEmailSender fakeEmailSender;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordResetVerificationRepository passwordResetVerificationRepository;

	@Autowired
	private SocialAccountRepository socialAccountRepository;

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
	@DisplayName("발송→확인이 성공하면 204이고 새 비밀번호로 로그인되며 기존 비밀번호는 401이다")
	void confirmSucceedsThenNewPasswordLogsInAndOldPasswordIsRejected() throws Exception {
		User user = persistEmailUser("confirm-success");
		String storedHash = passwordHashOf(user.getId());
		String code = sendCode(user.getEmail());

		mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody(user.getEmail(), code, NEW_PASSWORD)))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		assertThat(passwordHashOf(user.getId())).isNotEqualTo(storedHash);
		assertThat(passwordEncoder.matches(NEW_PASSWORD, passwordHashOf(user.getId()))).isTrue();
		assertThat(latestSentRow(user.getEmail()).getConsumedAt()).isNotNull();

		login(user.getEmail(), NEW_PASSWORD).andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty());
		login(user.getEmail(), PASSWORD).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("같은 인증번호를 다시 쓰면 400이고 비밀번호는 첫 성공 상태 그대로다")
	void sameCodeCannotBeUsedTwice() throws Exception {
		User user = persistEmailUser("confirm-reuse");
		String code = sendCode(user.getEmail());
		confirmExpectingNoContent(user.getEmail(), code, NEW_PASSWORD);
		String hashAfterFirstReset = passwordHashOf(user.getId());

		confirmExpectingError(user.getEmail(), code, "anotherSecret789", 400, "EMAIL_VERIFICATION_FAILED");

		assertThat(passwordHashOf(user.getId())).isEqualTo(hashAfterFirstReset);
		login(user.getEmail(), NEW_PASSWORD).andExpect(status().isOk());
		login(user.getEmail(), "anotherSecret789").andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("인증번호를 틀리면 400이 나가고도 attempt_count 증가는 DB에 커밋되며 password_hash는 그대로다")
	void wrongCodeCommitsAttemptCountIncrementWhileLeavingPasswordUnchanged() throws Exception {
		User user = persistEmailUser("confirm-attempt-commit");
		String storedHash = passwordHashOf(user.getId());
		String code = sendCode(user.getEmail());
		String wrongCode = wrongCodeFor(code);

		confirmExpectingError(user.getEmail(), wrongCode, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");
		assertThat(attemptCountOf(user.getEmail())).isEqualTo(1);

		confirmExpectingError(user.getEmail(), wrongCode, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");
		assertThat(attemptCountOf(user.getEmail())).isEqualTo(2);

		assertThat(consumedAtOf(user.getEmail())).isNull();
		assertThat(passwordHashOf(user.getId())).isEqualTo(storedHash);
		login(user.getEmail(), PASSWORD).andExpect(status().isOk());
	}

	@Test
	@DisplayName("같은 인증번호에 5회 실패하면 429이고 즉시 무효화되어 이후 정답을 넣어도 400이다")
	void fifthAttemptReturnsTooManyRequestsAndInvalidatesCodeEvenForTheCorrectAnswer() throws Exception {
		User user = persistEmailUser("confirm-attempt-limit");
		String storedHash = passwordHashOf(user.getId());
		String code = sendCode(user.getEmail());
		String wrongCode = wrongCodeFor(code);

		for (int attempt = 1; attempt <= 5; attempt++) {
			confirmExpectingError(user.getEmail(), wrongCode, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");
			assertThat(attemptCountOf(user.getEmail())).isEqualTo(attempt);
		}

		confirmExpectingError(user.getEmail(), code, NEW_PASSWORD, 429, "TOO_MANY_REQUESTS");
		assertThat(attemptCountOf(user.getEmail())).isEqualTo(6);
		assertThat(latestSentRow(user.getEmail()).getExpiresAt()).isBeforeOrEqualTo(LocalDateTime.now(clock));

		confirmExpectingError(user.getEmail(), code, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");

		assertThat(passwordHashOf(user.getId())).isEqualTo(storedHash);
		login(user.getEmail(), PASSWORD).andExpect(status().isOk());
		login(user.getEmail(), NEW_PASSWORD).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("동시에 들어온 오답 5건이 시도 1회로 뭉개지지 않고 각각 attempt_count에 반영된다")
	void concurrentWrongCodeAttemptsAreEachCountedInsteadOfCollapsingIntoOne() throws Exception {
		User user = persistEmailUser("confirm-concurrent-count");
		String storedHash = passwordHashOf(user.getId());
		String code = sendCode(user.getEmail());
		int concurrency = 5;

		List<Integer> statuses = fireConcurrentConfirms(user.getEmail(), wrongCodeFor(code), concurrency);

		assertThat(statuses).hasSize(concurrency).containsOnly(400);
		assertThat(attemptCountOf(user.getEmail())).isEqualTo(concurrency);
		assertThat(consumedAtOf(user.getEmail())).isNull();
		assertThat(passwordHashOf(user.getId())).isEqualTo(storedHash);
	}

	@Test
	@DisplayName("동시 6건을 쏴도 코드를 대조해 보는 요청은 5건뿐이고 6번째는 429와 함께 인증번호를 무효화한다")
	void concurrentAttemptsCannotOvershootTheFiveAttemptLimit() throws Exception {
		User user = persistEmailUser("confirm-concurrent-limit");
		String storedHash = passwordHashOf(user.getId());
		String code = sendCode(user.getEmail());

		List<Integer> statuses = fireConcurrentConfirms(user.getEmail(), wrongCodeFor(code), 6);

		assertThat(statuses).filteredOn(status -> status == 400).hasSize(5);
		assertThat(statuses).filteredOn(status -> status == 429).hasSize(1);
		assertThat(attemptCountOf(user.getEmail())).isEqualTo(6);
		assertThat(latestSentRow(user.getEmail()).getExpiresAt()).isBeforeOrEqualTo(LocalDateTime.now(clock));

		confirmExpectingError(user.getEmail(), code, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");
		assertThat(passwordHashOf(user.getId())).isEqualTo(storedHash);
		login(user.getEmail(), PASSWORD).andExpect(status().isOk());
	}

	@Test
	@DisplayName("한도를 크게 넘긴 동시 버스트에서도 코드가 무효화된 채로 끝나고 정답이 거부된다")
	void largeConcurrentBurstStillEndsWithTheCodeInvalidated() throws Exception {
		User user = persistEmailUser("confirm-concurrent-burst");
		String storedHash = passwordHashOf(user.getId());
		String code = sendCode(user.getEmail());

		List<Integer> statuses = fireConcurrentConfirms(user.getEmail(), wrongCodeFor(code), 8);

		assertThat(statuses).contains(429);
		assertThat(statuses).allMatch(status -> status == 400 || status == 429);
		assertThat(attemptCountOf(user.getEmail())).isGreaterThanOrEqualTo(6);
		assertThat(latestSentRow(user.getEmail()).getExpiresAt()).isBeforeOrEqualTo(LocalDateTime.now(clock));

		confirmExpectingError(user.getEmail(), code, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");
		assertThat(passwordHashOf(user.getId())).isEqualTo(storedHash);
	}

	@Test
	@DisplayName("확인 대상 조회는 앞선 트랜잭션이 커밋할 때까지 다음 읽기를 막아 읽기-판정-증가를 직렬화한다")
	void lockedLookupBlocksConcurrentReadUntilTheFirstTransactionCommits() throws Exception {
		User user = persistEmailUser("confirm-lock-serializes");
		sendCode(user.getEmail());
		String email = user.getEmail();

		CountDownLatch firstHoldsLock = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch secondFinishedReading = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);

		try {
			Future<?> first = pool.submit(() -> newTransaction().execute(status -> {
				PasswordResetVerification row = lockedLookup(email);
				row.incrementAttemptCount();
				passwordResetVerificationRepository.saveAndFlush(row);
				firstHoldsLock.countDown();
				awaitLatch(releaseFirst);
				return null;
			}));
			assertThat(firstHoldsLock.await(30, TimeUnit.SECONDS)).isTrue();

			Future<Integer> second = pool.submit(() -> newTransaction().execute(status -> {
				PasswordResetVerification row = lockedLookup(email);
				secondFinishedReading.countDown();
				int observed = row.getAttemptCount();
				row.incrementAttemptCount();
				passwordResetVerificationRepository.saveAndFlush(row);
				return observed;
			}));

			assertThat(secondFinishedReading.await(1, TimeUnit.SECONDS))
				.as("잠금이 없으면 두 번째 트랜잭션이 곧바로 같은 행을 읽어 증가분이 유실된다")
				.isFalse();

			releaseFirst.countDown();
			first.get(30, TimeUnit.SECONDS);
			assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(1);
		} finally {
			releaseFirst.countDown();
			pool.shutdownNow();
		}

		assertThat(attemptCountOf(email)).isEqualTo(2);
	}

	@Test
	@DisplayName("만료된 인증번호는 400이고 비밀번호가 바뀌지 않는다")
	void expiredCodeIsRejectedAndLeavesPasswordUnchanged() throws Exception {
		User user = persistEmailUser("confirm-expired");
		String storedHash = passwordHashOf(user.getId());
		String code = sendCode(user.getEmail());
		jdbcTemplate.update(
			"update password_reset_verifications set expires_at = ? where id = ?",
			LocalDateTime.now(clock).minusSeconds(1), latestSentRow(user.getEmail()).getId());

		confirmExpectingError(user.getEmail(), code, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");

		assertThat(passwordHashOf(user.getId())).isEqualTo(storedHash);
	}

	@Test
	@DisplayName("재발송으로 무효화된 이전 인증번호는 400이고 새 인증번호는 204로 통과한다")
	void codeInvalidatedByResendIsRejectedWhileTheNewCodeStillWorks() throws Exception {
		User user = persistEmailUser("confirm-resend");
		String firstCode = sendCode(user.getEmail());
		jdbcTemplate.update(
			"update password_reset_verifications set created_at = ? where email = ?",
			LocalDateTime.now(clock).minusSeconds(61), user.getEmail());
		String secondCode = sendCode(user.getEmail());
		assertThat(secondCode).isNotEqualTo(firstCode);

		confirmExpectingError(user.getEmail(), firstCode, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");
		confirmExpectingNoContent(user.getEmail(), secondCode, NEW_PASSWORD);

		login(user.getEmail(), NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	@DisplayName("거부 행이 발송 행보다 더 최신이어도 확인은 발송 행을 집어 정상 처리된다")
	void newerRejectedRowDoesNotShadowTheSentRow() throws Exception {
		User user = persistEmailUser("confirm-rejected-shadow");
		String code = sendCode(user.getEmail());
		passwordResetVerificationRepository.saveAndFlush(PasswordResetVerification
			.createRejected(user.getEmail(), LocalDateTime.now(clock).plusMinutes(1)));

		confirmExpectingNoContent(user.getEmail(), code, NEW_PASSWORD);

		login(user.getEmail(), NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	@DisplayName("타인의 이메일·인증번호 조합으로는 어떤 계정의 비밀번호도 바뀌지 않고 소유자의 인증번호도 소비되지 않는다")
	void otherUsersEmailAndCodeCombinationChangesNoPassword() throws Exception {
		User owner = persistEmailUser("confirm-isolation-owner");
		User attacker = persistEmailUser("confirm-isolation-attacker");
		String ownerHash = passwordHashOf(owner.getId());
		String attackerHash = passwordHashOf(attacker.getId());
		String ownerCode = sendCode(owner.getEmail());

		confirmExpectingError(attacker.getEmail(), ownerCode, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");

		assertThat(passwordHashOf(owner.getId())).isEqualTo(ownerHash);
		assertThat(passwordHashOf(attacker.getId())).isEqualTo(attackerHash);
		login(owner.getEmail(), PASSWORD).andExpect(status().isOk());
		login(attacker.getEmail(), PASSWORD).andExpect(status().isOk());

		assertThat(attemptCountOf(owner.getEmail())).isZero();
		confirmExpectingNoContent(owner.getEmail(), ownerCode, NEW_PASSWORD);
		assertThat(passwordHashOf(attacker.getId())).isEqualTo(attackerHash);
	}

	@Test
	@DisplayName("확인에 성공하면 확인 전 발급한 모든 Refresh Token이 폐기되어 /refresh가 401이다 — 전 기기 로그아웃")
	void successRevokesEveryRefreshTokenIssuedBeforeConfirm() throws Exception {
		User user = persistEmailUser("confirm-revoke");
		String probeDeviceToken = loginAndExtractRefreshToken(user.getEmail());
		refresh(probeDeviceToken).andExpect(status().isOk());

		String firstDeviceToken = loginAndExtractRefreshToken(user.getEmail());
		String secondDeviceToken = loginAndExtractRefreshToken(user.getEmail());
		assertThat(firstDeviceToken).isNotEqualTo(secondDeviceToken);

		String code = sendCode(user.getEmail());
		confirmExpectingNoContent(user.getEmail(), code, NEW_PASSWORD);

		refresh(firstDeviceToken).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		refresh(secondDeviceToken).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		login(user.getEmail(), NEW_PASSWORD).andExpect(status().isOk());
	}

	@Test
	@DisplayName("확인 실패는 기존 Refresh Token을 폐기하지 않는다 — 실패에 세션 폐기가 딸려가지 않는다")
	void failedConfirmDoesNotRevokeRefreshTokens() throws Exception {
		User user = persistEmailUser("confirm-failure-keeps-session");
		String refreshToken = loginAndExtractRefreshToken(user.getEmail());
		String code = sendCode(user.getEmail());

		confirmExpectingError(user.getEmail(), wrongCodeFor(code), NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");

		refresh(refreshToken).andExpect(status().isOk());
	}

	@Test
	@DisplayName("성공·실패 어느 경로에서도 이메일·닉네임·social_accounts·계좌·잔액·주문·체결이 변하지 않는다")
	void confirmNeverTouchesProfileSocialLinkAccountsOrLedger() throws Exception {
		User user = persistEmailUserWithSocialLink("confirm-invariance");
		SocialAccount linkBefore = socialAccountRepository.findByUserId(user.getId()).orElseThrow();
		List<AccountSnapshot> accountsBefore = snapshotAccounts(user.getId());
		assertThat(accountsBefore).hasSize(2)
			.extracting(AccountSnapshot::market)
			.containsExactlyInAnyOrder(Market.STOCK, Market.CRYPTO);
		long ordersBefore = countRows("orders");
		long tradesBefore = countRows("trades");
		long holdingsBefore = countRows("holdings");
		String code = sendCode(user.getEmail());

		confirmExpectingError(user.getEmail(), wrongCodeFor(code), NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");
		assertInvariants(user, linkBefore, accountsBefore, ordersBefore, tradesBefore, holdingsBefore);

		confirmExpectingNoContent(user.getEmail(), code, NEW_PASSWORD);
		assertInvariants(user, linkBefore, accountsBefore, ordersBefore, tradesBefore, holdingsBefore);
	}

	private void assertInvariants(
		User user,
		SocialAccount linkBefore,
		List<AccountSnapshot> accountsBefore,
		long ordersBefore,
		long tradesBefore,
		long holdingsBefore) {

		User reloaded = userRepository.findById(user.getId()).orElseThrow();
		assertThat(reloaded.getEmail()).isEqualTo(user.getEmail());
		assertThat(reloaded.getNickname()).isEqualTo(user.getNickname());

		SocialAccount linkAfter = socialAccountRepository.findByUserId(user.getId()).orElseThrow();
		assertThat(linkAfter.getId()).isEqualTo(linkBefore.getId());
		assertThat(linkAfter.getProvider()).isEqualTo(linkBefore.getProvider());
		assertThat(linkAfter.getProviderUserId()).isEqualTo(linkBefore.getProviderUserId());

		assertThat(snapshotAccounts(user.getId())).isEqualTo(accountsBefore);
		assertThat(countRows("orders")).isEqualTo(ordersBefore);
		assertThat(countRows("trades")).isEqualTo(tradesBefore);
		assertThat(countRows("holdings")).isEqualTo(holdingsBefore);
	}

	private String sendCode(String email) throws Exception {
		mockMvc.perform(post(SEND_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\"}"))
			.andExpect(status().isAccepted());

		List<FakeEmailSender.SentEmail> sent = fakeEmailSender.getSentEmails().stream()
			.filter(candidate -> candidate.toEmail().equals(email))
			.toList();
		assertThat(sent).isNotEmpty();
		return sent.get(sent.size() - 1).code();
	}

	private PasswordResetVerification lockedLookup(String email) {
		return passwordResetVerificationRepository
			.findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(email)
			.orElseThrow();
	}

	private TransactionTemplate newTransaction() {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template;
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			latch.await(30, TimeUnit.SECONDS);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private List<Integer> fireConcurrentConfirms(String email, String code, int count) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(pool.submit(() -> {
					startGate.await();
					return mockMvc.perform(post(CONFIRM_PATH)
						.contentType(MediaType.APPLICATION_JSON)
						.content(confirmBody(email, code, NEW_PASSWORD)))
						.andReturn()
						.getResponse()
						.getStatus();
				}));
			}
			startGate.countDown();

			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(60, TimeUnit.SECONDS));
			}
			return statuses;
		} finally {
			pool.shutdownNow();
		}
	}

	private void confirmExpectingNoContent(String email, String code, String newPassword) throws Exception {
		mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody(email, code, newPassword)))
			.andExpect(status().isNoContent());
	}

	private void confirmExpectingError(
		String email, String code, String newPassword, int status, String errorCode) throws Exception {

		mockMvc.perform(post(CONFIRM_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content(confirmBody(email, code, newPassword)))
			.andExpect(status().is(status))
			.andExpect(jsonPath("$.error.code").value(errorCode))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post(LOGIN_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post(REFRESH_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"refreshToken\":\"" + refreshToken + "\"}"));
	}

	private String loginAndExtractRefreshToken(String email) throws Exception {
		String body = login(email, PASSWORD)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return extractJsonString(body, "refreshToken");
	}

	private static String extractJsonString(String json, String field) {
		String marker = "\"" + field + "\":\"";
		int start = json.indexOf(marker);
		assertThat(start).as("응답에 %s 필드가 있어야 한다: %s", field, json).isNotNegative();
		start += marker.length();
		return json.substring(start, json.indexOf('"', start));
	}

	private static String confirmBody(String email, String code, String newPassword) {
		return "{\"email\":\"" + email + "\",\"code\":\"" + code + "\",\"newPassword\":\"" + newPassword + "\"}";
	}

	private String passwordHashOf(Long userId) {
		return jdbcTemplate.queryForObject("select password_hash from users where id = ?", String.class, userId);
	}

	private int attemptCountOf(String email) {
		return jdbcTemplate.queryForObject(
			"select attempt_count from password_reset_verifications"
				+ " where email = ? and code_hash is not null order by created_at desc, id desc limit 1",
			Integer.class, email);
	}

	private LocalDateTime consumedAtOf(String email) {
		return jdbcTemplate.queryForObject(
			"select consumed_at from password_reset_verifications"
				+ " where email = ? and code_hash is not null order by created_at desc, id desc limit 1",
			LocalDateTime.class, email);
	}

	private PasswordResetVerification latestSentRow(String email) {
		Long id = jdbcTemplate.queryForObject(
			"select id from password_reset_verifications"
				+ " where email = ? and code_hash is not null order by created_at desc, id desc limit 1",
			Long.class, email);
		return passwordResetVerificationRepository.findById(id).orElseThrow();
	}

	private long countRows(String table) {
		return jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
	}

	private List<AccountSnapshot> snapshotAccounts(Long userId) {
		return accountRepository.findAllByUserId(userId).stream()
			.map(AccountSnapshot::from)
			.sorted(Comparator.comparing(AccountSnapshot::market))
			.toList();
	}

	private User persistEmailUser(String scenario) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.saveAndFlush(User.create(
			uniqueEmail(scenario), passwordEncoder.encode(PASSWORD), uniqueNickname(scenario), now));
		accountService.createAccountsFor(user);
		return user;
	}

	private User persistEmailUserWithSocialLink(String scenario) {
		User user = persistEmailUser(scenario);
		socialAccountRepository.saveAndFlush(SocialAccount.create(
			user, OAuthProviderName.KAKAO, "oauth-user-" + UUID.randomUUID(), LocalDateTime.now(clock)));
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
