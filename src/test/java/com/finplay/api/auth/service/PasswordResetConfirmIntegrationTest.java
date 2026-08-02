// 실제 MySQL과 HTTP 경로로 비밀번호 재설정 확인의 성공·재사용·5회초과·만료·재발송무효화·타인격리와
// 검증 실패 시 attempt_count 증가 커밋·전 기기 로그아웃·회원 자산 불변을 검증하는 통합 테스트다.
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.PasswordResetVerification;
import com.finplay.api.auth.domain.SocialAccount;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.email.FakeEmailSender;
import com.finplay.api.auth.oauth.OAuthProviderName;
import com.finplay.api.auth.repository.PasswordResetVerificationRepository;
import com.finplay.api.auth.repository.SocialAccountRepository;
import com.finplay.api.auth.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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

// @Transactional을 붙이지 않는다 — 이 테스트의 핵심이 "예외가 나가고도 커밋되는가"라서 롤백시키면 검증 자체가 사라진다.
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
			// 새 토큰 쌍을 응답에 담지 않는다 — 재설정은 자동 로그인이 아니다.
			.andExpect(content().string(""));

		assertThat(passwordHashOf(user.getId())).isNotEqualTo(storedHash);
		assertThat(passwordEncoder.matches(NEW_PASSWORD, passwordHashOf(user.getId()))).isTrue();
		// 소비·해시 교체가 함께 커밋됐다.
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
		// noRollbackFor가 빠지면 증가분이 예외와 함께 되돌아가 5회 제한에 영원히 도달하지 못한다(무제한 대입).
		User user = persistEmailUser("confirm-attempt-commit");
		String storedHash = passwordHashOf(user.getId());
		String code = sendCode(user.getEmail());
		String wrongCode = wrongCodeFor(code);

		confirmExpectingError(user.getEmail(), wrongCode, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");
		assertThat(attemptCountOf(user.getEmail())).isEqualTo(1);

		confirmExpectingError(user.getEmail(), wrongCode, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");
		assertThat(attemptCountOf(user.getEmail())).isEqualTo(2);

		// 실패 경로에서는 소비·교체가 하나도 일어나지 않는다.
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

		// 6번째는 정답을 보내도 한도 판정이 먼저다.
		confirmExpectingError(user.getEmail(), code, NEW_PASSWORD, 429, "TOO_MANY_REQUESTS");
		assertThat(attemptCountOf(user.getEmail())).isEqualTo(6);
		// 무효화도 커밋되어야 다음 요청부터 막힌다.
		assertThat(latestSentRow(user.getEmail()).getExpiresAt()).isBeforeOrEqualTo(LocalDateTime.now(clock));

		confirmExpectingError(user.getEmail(), code, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");

		assertThat(passwordHashOf(user.getId())).isEqualTo(storedHash);
		login(user.getEmail(), PASSWORD).andExpect(status().isOk());
		login(user.getEmail(), NEW_PASSWORD).andExpect(status().isUnauthorized());
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
		// 60초 재발송 간격은 created_at 기준이라, 실제 대기 대신 이전 행을 뒤로 당겨 창을 지난 것처럼 만든다.
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
		// #115가 남기는 거부 행(code_hash·expires_at NULL)을 발송 행보다 뒤 시각으로 끼워 넣는다.
		passwordResetVerificationRepository.saveAndFlush(PasswordResetVerification
			.createRejected(user.getEmail(), LocalDateTime.now(clock).plusMinutes(1)));

		// 조회에서 거부 행을 집으면 여기서 NPE(500)가 난다.
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

		// 공격자 이메일 + 소유자 인증번호 — 공격자에게는 발송 행이 없어 400이다.
		confirmExpectingError(attacker.getEmail(), ownerCode, NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");

		assertThat(passwordHashOf(owner.getId())).isEqualTo(ownerHash);
		assertThat(passwordHashOf(attacker.getId())).isEqualTo(attackerHash);
		login(owner.getEmail(), PASSWORD).andExpect(status().isOk());
		login(attacker.getEmail(), PASSWORD).andExpect(status().isOk());

		// 타인의 시도가 소유자의 인증번호를 소비하지도, 시도 횟수를 깎지도 않았다.
		assertThat(attemptCountOf(owner.getEmail())).isZero();
		confirmExpectingNoContent(owner.getEmail(), ownerCode, NEW_PASSWORD);
		assertThat(passwordHashOf(attacker.getId())).isEqualTo(attackerHash);
	}

	@Test
	@DisplayName("확인에 성공하면 확인 전 발급한 모든 Refresh Token이 폐기되어 /refresh가 401이다 — 전 기기 로그아웃")
	void successRevokesEveryRefreshTokenIssuedBeforeConfirm() throws Exception {
		User user = persistEmailUser("confirm-revoke");
		// 갱신은 토큰을 회전(기존 행 폐기)시키므로, 사전 확인용 세션을 따로 두고 검증 대상 두 개는 확인 전까지 쓰지 않는다.
		// 검증 대상을 미리 갱신해 보면 그 401이 회전 때문인지 재설정 때문인지 구분되지 않는다.
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
		// 재로그인은 새 비밀번호로만 가능하다.
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
		// 비밀번호가 있으면서 OAuth 연결도 가진 회원이라 social_accounts 불변을 함께 볼 수 있다.
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

		// 실패 경로.
		confirmExpectingError(user.getEmail(), wrongCodeFor(code), NEW_PASSWORD, 400, "EMAIL_VERIFICATION_FAILED");
		assertInvariants(user, linkBefore, accountsBefore, ordersBefore, tradesBefore, holdingsBefore);

		// 성공 경로.
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

		// 다른 시나리오의 발송이 섞여 있어도 이 이메일의 마지막 코드를 집는다.
		List<FakeEmailSender.SentEmail> sent = fakeEmailSender.getSentEmails().stream()
			.filter(candidate -> candidate.toEmail().equals(email))
			.toList();
		assertThat(sent).isNotEmpty();
		return sent.get(sent.size() - 1).code();
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

	// 응답 스키마 검증은 다른 테스트가 하므로 여기서는 토큰 값만 꺼낸다.
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

	// 영속성 컨텍스트 캐시를 우회해 실제 커밋된 값을 읽는다.
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

	// 비밀번호도 있고 OAuth 연결도 있는 회원 — 재설정 대상이면서 social_accounts 불변을 관찰할 수 있다.
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
