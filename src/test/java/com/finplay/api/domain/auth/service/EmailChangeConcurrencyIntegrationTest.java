package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.email.FakeEmailSender;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EmailChangeConcurrencyIntegrationTest {

	private static final String SEND_VERIFICATION_PATH = "/api/auth/email-verifications";
	private static final String CONFIRM_VERIFICATION_PATH = "/api/auth/email-verifications/confirm";
	private static final String SIGNUP_PATH = "/api/auth/signup";
	private static final String LOGIN_PATH = "/api/auth/login";
	private static final String EMAIL_CHANGE_PATH = "/api/auth/email-changes";
	private static final String EMAIL_CHANGE_CONFIRM_PATH = "/api/auth/email-changes/confirm";
	private static final String PASSWORD = "password123";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private FakeEmailSender fakeEmailSender;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock clock;

	@Test
	@DisplayName("동시에 들어온 오답 5건이 시도 1회로 뭉개지지 않고 각각 attempt_count에 반영된다")
	void concurrentWrongCodeAttemptsAreEachCountedInsteadOfCollapsingIntoOne() throws Exception {
		EmailChangeFixture fixture = prepareEmailChange("change-concurrent-count");

		List<Integer> statuses = fireConcurrentConfirms(fixture, wrongCodeFor(fixture.code()), 5);

		assertThat(statuses).hasSize(5).containsOnly(400);
		assertThat(attemptCountOf(fixture)).isEqualTo(5);
		assertThat(consumedAtOf(fixture)).isNull();
		assertThat(emailOf(fixture.userId())).isEqualTo(fixture.currentEmail());
	}

	@Test
	@DisplayName("동시 6건을 쏴도 코드를 대조해 보는 요청은 5건뿐이고 6번째는 429와 함께 인증번호를 무효화한다")
	void concurrentAttemptsCannotOvershootTheFiveAttemptLimit() throws Exception {
		EmailChangeFixture fixture = prepareEmailChange("change-concurrent-limit");

		List<Integer> statuses = fireConcurrentConfirms(fixture, wrongCodeFor(fixture.code()), 6);

		assertThat(statuses).filteredOn(status -> status == 400).hasSize(5);
		assertThat(statuses).filteredOn(status -> status == 429).hasSize(1);
		assertThat(attemptCountOf(fixture)).isEqualTo(6);
		assertThat(expiresAtOf(fixture)).isBeforeOrEqualTo(LocalDateTime.now(clock));
		assertThat(emailOf(fixture.userId())).isEqualTo(fixture.currentEmail());
	}

	@Test
	@DisplayName("한도를 크게 넘긴 동시 버스트에서도 인증번호가 무효화된 채로 끝나고 이후 정답이 거부된다")
	void largeConcurrentBurstStillEndsWithTheCodeInvalidated() throws Exception {
		EmailChangeFixture fixture = prepareEmailChange("change-concurrent-burst");

		List<Integer> statuses = fireConcurrentConfirms(fixture, wrongCodeFor(fixture.code()), 8);

		assertThat(statuses).contains(429);
		assertThat(statuses).allMatch(status -> status == 400 || status == 429);
		assertThat(attemptCountOf(fixture)).isGreaterThanOrEqualTo(6);
		assertThat(expiresAtOf(fixture)).isBeforeOrEqualTo(LocalDateTime.now(clock));

		confirmEmailChange(fixture, fixture.code())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_FAILED"));
		assertThat(consumedAtOf(fixture)).isNull();
		assertThat(emailOf(fixture.userId())).isEqualTo(fixture.currentEmail());
	}

	private List<Integer> fireConcurrentConfirms(EmailChangeFixture fixture, String code, int count)
		throws Exception {

		ExecutorService pool = Executors.newFixedThreadPool(count);
		CountDownLatch allThreadsReady = new CountDownLatch(count);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(pool.submit(() -> {
					allThreadsReady.countDown();
					startGate.await();
					return confirmEmailChange(fixture, code).andReturn().getResponse().getStatus();
				}));
			}
			assertThat(allThreadsReady.await(30, TimeUnit.SECONDS)).isTrue();
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

	private ResultActions confirmEmailChange(EmailChangeFixture fixture, String code) throws Exception {

		return mockMvc.perform(post(EMAIL_CHANGE_CONFIRM_PATH)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.accessToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"newEmail\":\"" + fixture.newEmail() + "\",\"code\":\"" + code + "\"}"));
	}

	private EmailChangeFixture prepareEmailChange(String scenario) throws Exception {
		String email = uniqueEmail(scenario);
		String accessToken = signupAndLogin(email, uniqueNickname(scenario));
		long userId = userIdOf(email);

		String newEmail = uniqueEmail(scenario + "-new");
		mockMvc.perform(post(EMAIL_CHANGE_PATH)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"newEmail\":\"" + newEmail + "\",\"currentPassword\":\"" + PASSWORD + "\"}"))
			.andExpect(status().isAccepted());

		return new EmailChangeFixture(userId, email, accessToken, newEmail, lastCodeSentTo(newEmail));
	}

	private String signupAndLogin(String email, String nickname) throws Exception {
		mockMvc.perform(post(SEND_VERIFICATION_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\"}"))
			.andExpect(status().isAccepted());

		String confirmBody = mockMvc.perform(post(CONFIRM_VERIFICATION_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"code\":\"" + lastCodeSentTo(email) + "\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		mockMvc.perform(post(SIGNUP_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"nickname\":\"" + nickname + "\",\"password\":\"" + PASSWORD
				+ "\",\"termsAgreed\":true,\"signupVerificationToken\":\""
				+ extractJsonString(confirmBody, "signupVerificationToken") + "\"}"))
			.andExpect(status().isCreated());

		String loginBody = mockMvc.perform(post(LOGIN_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return extractJsonString(loginBody, "accessToken");
	}

	private String lastCodeSentTo(String email) {
		List<FakeEmailSender.SentEmail> sent = fakeEmailSender.getSentEmails().stream()
			.filter(candidate -> candidate.toEmail().equals(email))
			.toList();
		assertThat(sent).as("%s로 발송된 인증번호가 있어야 한다", email).isNotEmpty();
		return sent.get(sent.size() - 1).code();
	}

	private int attemptCountOf(EmailChangeFixture fixture) {
		return jdbcTemplate.queryForObject(
			"select attempt_count from email_change_verifications"
				+ " where user_id = ? and new_email = ? order by created_at desc, id desc limit 1",
			Integer.class, fixture.userId(), fixture.newEmail());
	}

	private LocalDateTime consumedAtOf(EmailChangeFixture fixture) {
		return jdbcTemplate.queryForObject(
			"select consumed_at from email_change_verifications"
				+ " where user_id = ? and new_email = ? order by created_at desc, id desc limit 1",
			LocalDateTime.class, fixture.userId(), fixture.newEmail());
	}

	private LocalDateTime expiresAtOf(EmailChangeFixture fixture) {
		return jdbcTemplate.queryForObject(
			"select expires_at from email_change_verifications"
				+ " where user_id = ? and new_email = ? order by created_at desc, id desc limit 1",
			LocalDateTime.class, fixture.userId(), fixture.newEmail());
	}

	private String emailOf(long userId) {
		return jdbcTemplate.queryForObject("select email from users where id = ?", String.class, userId);
	}

	private long userIdOf(String email) {
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private static String extractJsonString(String json, String field) {
		String marker = "\"" + field + "\":\"";
		int start = json.indexOf(marker);
		assertThat(start).as("응답에 %s 필드가 있어야 한다: %s", field, json).isNotNegative();
		start += marker.length();
		return json.substring(start, json.indexOf('"', start));
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

	private record EmailChangeFixture(
		long userId,
		String currentEmail,
		String accessToken,
		String newEmail,
		String code) {
	}
}
