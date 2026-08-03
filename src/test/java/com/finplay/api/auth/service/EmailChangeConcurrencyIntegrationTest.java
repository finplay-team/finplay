// 이메일 변경 인증번호 확인이 동시 요청에서도 시도 횟수를 유실 없이 집계하는지 검증하는 통합 테스트 (이슈 #121, D7)
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.email.FakeEmailSender;
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

// @Transactional을 붙이지 않는다 — 각 요청의 커밋 결과를 실제로 관찰하는 것이 이 테스트의 목적이라 롤백시키면 검증이 사라진다.
// 픽스처는 전부 프로덕션에 실제로 존재하는 경로로만 만든다 — 인증번호 발송·확인 → 가입 → 로그인으로 Bearer 토큰을 얻고,
// 이메일 변경 인증번호도 보호된 발송 엔드포인트로 받는다. 저장소를 직접 조작해 회원 행을 만들면 프로덕션에 없는 상태가 섞여
// 테스트가 통과해도 결함이 살아남는다 (#115 리뷰에서 실제로 발생: password_hash NULL 픽스처).
// 상태 확인은 전부 jdbcTemplate으로 한다 — 확인 경로의 조회 쿼리(findFirstByUserIdAndNewEmailOrderByCreatedAtDesc)는
// 곧 @Lock(PESSIMISTIC_WRITE)이 붙으므로 트랜잭션 밖에서 그 쿼리를 부르면 MySQL이 read-only 트랜잭션의
// SELECT ... FOR UPDATE를 거부한다 (D4).
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
		// 회귀 대상 — 잠금 없이 읽으면 동시 요청이 같은 attempt_count를 읽고 같은 값 + 1을 써서 N건이 1회로 계산된다.
		// 그러면 PRD AUTH-005의 "최대 5회"가 동시 요청 앞에서 사실이 아니게 된다.
		EmailChangeFixture fixture = prepareEmailChange("change-concurrent-count");

		List<Integer> statuses = fireConcurrentConfirms(fixture, wrongCodeFor(fixture.code()), 5);

		// 5건 모두 한도 안이므로 전부 400이고, 유실 없이 정확히 5회로 세어져야 한다.
		assertThat(statuses).hasSize(5).containsOnly(400);
		assertThat(attemptCountOf(fixture)).isEqualTo(5);
		// 실패 경로에서는 소비·이메일 변경이 하나도 일어나지 않는다.
		assertThat(consumedAtOf(fixture)).isNull();
		assertThat(emailOf(fixture.userId())).isEqualTo(fixture.currentEmail());
	}

	@Test
	@DisplayName("동시 6건을 쏴도 코드를 대조해 보는 요청은 5건뿐이고 6번째는 429와 함께 인증번호를 무효화한다")
	void concurrentAttemptsCannotOvershootTheFiveAttemptLimit() throws Exception {
		EmailChangeFixture fixture = prepareEmailChange("change-concurrent-limit");

		List<Integer> statuses = fireConcurrentConfirms(fixture, wrongCodeFor(fixture.code()), 6);

		// 행 잠금으로 직렬화되면 순서는 하나뿐이다 — 1~5번째는 한도 안의 오답이라 400,
		// 6번째는 attempt_count가 5에 도달해 코드를 대조해 보지도 못하고 429 + 즉시 무효화다.
		// 잠금이 없으면 6건이 같은 값을 읽어 전부 400이 되고 429가 한 건도 나오지 않는다.
		assertThat(statuses).filteredOn(status -> status == 400).hasSize(5);
		assertThat(statuses).filteredOn(status -> status == 429).hasSize(1);
		assertThat(attemptCountOf(fixture)).isEqualTo(6);
		assertThat(expiresAtOf(fixture)).isBeforeOrEqualTo(LocalDateTime.now(clock));
		assertThat(emailOf(fixture.userId())).isEqualTo(fixture.currentEmail());
	}

	@Test
	@DisplayName("한도를 크게 넘긴 동시 버스트에서도 인증번호가 무효화된 채로 끝나고 이후 정답이 거부된다")
	void largeConcurrentBurstStillEndsWithTheCodeInvalidated() throws Exception {
		// 6건을 넘는 버스트의 꼬리 응답은 429일 수도 400일 수도 있다 — now가 행 잠금을 잡기 전에 찍히기 때문이다.
		// 그래서 개수 대신 "한도에 도달했고 인증번호가 죽었다"는 최종 상태만 단정한다.
		EmailChangeFixture fixture = prepareEmailChange("change-concurrent-burst");

		List<Integer> statuses = fireConcurrentConfirms(fixture, wrongCodeFor(fixture.code()), 8);

		// 잠금이 없으면 8건이 시도 1~2회로 뭉개져 한도에 닿지 못하고 429가 하나도 나오지 않는다.
		assertThat(statuses).contains(429);
		assertThat(statuses).allMatch(status -> status == 400 || status == 429);
		assertThat(attemptCountOf(fixture)).isGreaterThanOrEqualTo(6);
		assertThat(expiresAtOf(fixture)).isBeforeOrEqualTo(LocalDateTime.now(clock));

		// 무효화 이후에는 정답도 통하지 않는다.
		confirmEmailChange(fixture, fixture.code())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("EMAIL_VERIFICATION_FAILED"));
		assertThat(consumedAtOf(fixture)).isNull();
		assertThat(emailOf(fixture.userId())).isEqualTo(fixture.currentEmail());
	}

	// 같은 회원·같은 새 이메일에 동시에 확인 요청을 쏘고 각 응답 상태를 모은다.
	// 커넥션 풀 기본값(10)을 넘기지 않도록 동시 요청 수는 여유를 두고 잡는다.
	private List<Integer> fireConcurrentConfirms(EmailChangeFixture fixture, String code, int count)
		throws Exception {

		ExecutorService pool = Executors.newFixedThreadPool(count);
		CountDownLatch allThreadsReady = new CountDownLatch(count);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(pool.submit(() -> {
					// 모든 스레드가 준비된 뒤 동시에 출발해야 겹침이 최대가 된다.
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

	// 가입 인증번호 발송·확인 → 가입 → 로그인 → 보호된 이메일 변경 발송까지 전부 실제 엔드포인트로 만든다.
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

	// 다른 시나리오의 발송이 섞여 있어도 이 주소로 간 마지막 코드를 집는다.
	private String lastCodeSentTo(String email) {
		List<FakeEmailSender.SentEmail> sent = fakeEmailSender.getSentEmails().stream()
			.filter(candidate -> candidate.toEmail().equals(email))
			.toList();
		assertThat(sent).as("%s로 발송된 인증번호가 있어야 한다", email).isNotEmpty();
		return sent.get(sent.size() - 1).code();
	}

	// 영속성 컨텍스트 캐시를 우회해 실제 커밋된 값을 읽는다.
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

	// 응답 스키마 검증은 다른 테스트가 하므로 여기서는 토큰 값만 꺼낸다.
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
