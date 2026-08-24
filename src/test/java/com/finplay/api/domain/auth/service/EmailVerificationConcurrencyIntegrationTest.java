// 가입 인증번호 확인이 동시 요청에서도 시도 횟수를 유실 없이 집계하는지 검증하는 통합 테스트 (이슈 #121, D7)
package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.EmailVerification;
import com.finplay.api.domain.auth.repository.EmailVerificationRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// @Transactional을 붙이지 않는다 — 각 스레드의 커밋 결과를 실제로 관찰하는 것이 이 테스트의 목적이라 롤백시키면 검증이 사라진다.
// 상태 확인은 전부 jdbcTemplate으로 한다 — 확인 경로의 조회 쿼리는 곧 @Lock(PESSIMISTIC_WRITE)이 붙으므로
// 트랜잭션 밖에서 그 쿼리를 부르면 MySQL이 read-only 트랜잭션의 SELECT ... FOR UPDATE를 거부한다 (D4).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EmailVerificationConcurrencyIntegrationTest {

	private static final String SECRET = "test-email-verification-secret";
	private static final String CORRECT_CODE = "123456";
	private static final String INCORRECT_CODE = "000000";

	@Autowired
	private EmailVerificationService emailVerificationService;

	@Autowired
	private EmailVerificationRepository emailVerificationRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock clock;

	@Test
	@DisplayName("동시에 들어온 오답 5건이 시도 1회로 뭉개지지 않고 각각 attempt_count에 반영된다")
	void concurrentWrongCodeAttemptsAreEachCountedInsteadOfCollapsingIntoOne() throws Exception {
		// 회귀 대상 — 잠금 없이 읽으면 동시 요청이 같은 attempt_count를 읽고 같은 값 + 1을 써서 N건이 1회로 계산된다.
		// 그러면 PRD AUTH-004의 "최대 5회"가 동시 요청 앞에서 사실이 아니게 된다.
		String email = persistVerification();

		List<ErrorCode> results = fireConcurrentConfirms(email, INCORRECT_CODE, 5);

		// 5건 모두 한도 안이므로 전부 400이고, 유실 없이 정확히 5회로 세어져야 한다.
		assertThat(results).hasSize(5).containsOnly(ErrorCode.EMAIL_VERIFICATION_FAILED);
		assertThat(attemptCountOf(email)).isEqualTo(5);
		// 실패 경로에서는 확인·토큰 발급이 하나도 일어나지 않는다.
		assertThat(verifiedAtOf(email)).isNull();
		assertThat(tokenHashOf(email)).isNull();
	}

	@Test
	@DisplayName("동시 6건을 쏴도 코드를 대조해 보는 요청은 5건뿐이고 6번째는 429와 함께 인증번호를 무효화한다")
	void concurrentAttemptsCannotOvershootTheFiveAttemptLimit() throws Exception {
		String email = persistVerification();

		List<ErrorCode> results = fireConcurrentConfirms(email, INCORRECT_CODE, 6);

		// 행 잠금으로 직렬화되면 순서는 하나뿐이다 — 1~5번째는 한도 안의 오답이라 400,
		// 6번째는 attempt_count가 5에 도달해 코드를 대조해 보지도 못하고 429 + 즉시 무효화다.
		// 잠금이 없으면 6건이 같은 값을 읽어 전부 400이 되고 429가 한 건도 나오지 않는다.
		assertThat(results).filteredOn(ErrorCode.EMAIL_VERIFICATION_FAILED::equals).hasSize(5);
		assertThat(results).filteredOn(ErrorCode.TOO_MANY_REQUESTS::equals).hasSize(1);
		assertThat(attemptCountOf(email)).isEqualTo(6);
		assertThat(expiresAtOf(email)).isBeforeOrEqualTo(LocalDateTime.now(clock));
	}

	@Test
	@DisplayName("한도를 크게 넘긴 동시 버스트에서도 인증번호가 무효화된 채로 끝나고 이후 정답이 거부된다")
	void largeConcurrentBurstStillEndsWithTheCodeInvalidated() throws Exception {
		// 6건을 넘는 버스트의 꼬리 응답은 429일 수도 400일 수도 있다 — now가 행 잠금을 잡기 전에 찍히기 때문이다.
		// 그래서 개수 대신 "한도에 도달했고 인증번호가 죽었다"는 최종 상태만 단정한다.
		String email = persistVerification();

		List<ErrorCode> results = fireConcurrentConfirms(email, INCORRECT_CODE, 8);

		// 잠금이 없으면 8건이 시도 1~2회로 뭉개져 한도에 닿지 못하고 429가 하나도 나오지 않는다.
		assertThat(results).contains(ErrorCode.TOO_MANY_REQUESTS);
		assertThat(results).allMatch(errorCode -> errorCode == ErrorCode.EMAIL_VERIFICATION_FAILED
			|| errorCode == ErrorCode.TOO_MANY_REQUESTS);
		assertThat(attemptCountOf(email)).isGreaterThanOrEqualTo(6);
		assertThat(expiresAtOf(email)).isBeforeOrEqualTo(LocalDateTime.now(clock));

		// 무효화 이후에는 정답도 통하지 않는다.
		assertThatThrownBy(() -> emailVerificationService.confirmVerificationCode(email, CORRECT_CODE))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
		assertThat(verifiedAtOf(email)).isNull();
	}

	// 같은 이메일·같은 인증번호로 동시에 확인을 호출하고 각 결과(성공은 null, 실패는 ErrorCode)를 모은다.
	// 커넥션 풀 기본값(10)을 넘기지 않도록 동시 요청 수는 여유를 두고 잡는다.
	private List<ErrorCode> fireConcurrentConfirms(String email, String code, int count) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		CountDownLatch allThreadsReady = new CountDownLatch(count);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<ErrorCode>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(pool.submit(() -> {
					// 모든 스레드가 준비된 뒤 동시에 출발해야 겹침이 최대가 된다.
					allThreadsReady.countDown();
					startGate.await();
					try {
						emailVerificationService.confirmVerificationCode(email, code);
						return null;
					} catch (BusinessException ex) {
						return ex.getErrorCode();
					}
				}));
			}
			assertThat(allThreadsReady.await(30, TimeUnit.SECONDS)).isTrue();
			startGate.countDown();

			List<ErrorCode> results = new ArrayList<>();
			for (Future<ErrorCode> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

	// 발송 경로(60초 재발송 제한)를 타지 않고 확인 대상 행만 직접 만든다 — 검증 대상은 확인 경로다.
	private String persistVerification() {
		String email = uniqueEmail();
		LocalDateTime now = LocalDateTime.now(clock);
		emailVerificationRepository.saveAndFlush(EmailVerification.create(
			email, hmac(CORRECT_CODE), now.plusMinutes(5), now));
		return email;
	}

	// 영속성 컨텍스트 캐시를 우회해 실제 커밋된 값을 읽는다.
	private int attemptCountOf(String email) {
		return jdbcTemplate.queryForObject(
			"select attempt_count from email_verifications where email = ? order by created_at desc, id desc limit 1",
			Integer.class, email);
	}

	private LocalDateTime expiresAtOf(String email) {
		return jdbcTemplate.queryForObject(
			"select expires_at from email_verifications where email = ? order by created_at desc, id desc limit 1",
			LocalDateTime.class, email);
	}

	private LocalDateTime verifiedAtOf(String email) {
		return jdbcTemplate.queryForObject(
			"select verified_at from email_verifications where email = ? order by created_at desc, id desc limit 1",
			LocalDateTime.class, email);
	}

	private String tokenHashOf(String email) {
		return jdbcTemplate.queryForObject(
			"select token_hash from email_verifications where email = ? order by created_at desc, id desc limit 1",
			String.class, email);
	}

	private static String uniqueEmail() {
		return "verification-concurrency-" + UUID.randomUUID() + "@finplay.com";
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
