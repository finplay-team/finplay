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
		String email = persistVerification();

		List<ErrorCode> results = fireConcurrentConfirms(email, INCORRECT_CODE, 5);

		assertThat(results).hasSize(5).containsOnly(ErrorCode.EMAIL_VERIFICATION_FAILED);
		assertThat(attemptCountOf(email)).isEqualTo(5);
		assertThat(verifiedAtOf(email)).isNull();
		assertThat(tokenHashOf(email)).isNull();
	}

	@Test
	@DisplayName("동시 6건을 쏴도 코드를 대조해 보는 요청은 5건뿐이고 6번째는 429와 함께 인증번호를 무효화한다")
	void concurrentAttemptsCannotOvershootTheFiveAttemptLimit() throws Exception {
		String email = persistVerification();

		List<ErrorCode> results = fireConcurrentConfirms(email, INCORRECT_CODE, 6);

		assertThat(results).filteredOn(ErrorCode.EMAIL_VERIFICATION_FAILED::equals).hasSize(5);
		assertThat(results).filteredOn(ErrorCode.TOO_MANY_REQUESTS::equals).hasSize(1);
		assertThat(attemptCountOf(email)).isEqualTo(6);
		assertThat(expiresAtOf(email)).isBeforeOrEqualTo(LocalDateTime.now(clock));
	}

	@Test
	@DisplayName("한도를 크게 넘긴 동시 버스트에서도 인증번호가 무효화된 채로 끝나고 이후 정답이 거부된다")
	void largeConcurrentBurstStillEndsWithTheCodeInvalidated() throws Exception {
		String email = persistVerification();

		List<ErrorCode> results = fireConcurrentConfirms(email, INCORRECT_CODE, 8);

		assertThat(results).contains(ErrorCode.TOO_MANY_REQUESTS);
		assertThat(results).allMatch(errorCode -> errorCode == ErrorCode.EMAIL_VERIFICATION_FAILED
			|| errorCode == ErrorCode.TOO_MANY_REQUESTS);
		assertThat(attemptCountOf(email)).isGreaterThanOrEqualTo(6);
		assertThat(expiresAtOf(email)).isBeforeOrEqualTo(LocalDateTime.now(clock));

		assertThatThrownBy(() -> emailVerificationService.confirmVerificationCode(email, CORRECT_CODE))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_VERIFICATION_FAILED);
		assertThat(verifiedAtOf(email)).isNull();
	}

	private List<ErrorCode> fireConcurrentConfirms(String email, String code, int count) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		CountDownLatch allThreadsReady = new CountDownLatch(count);
		CountDownLatch startGate = new CountDownLatch(1);
		try {
			List<Future<ErrorCode>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(pool.submit(() -> {
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

	private String persistVerification() {
		String email = uniqueEmail();
		LocalDateTime now = LocalDateTime.now(clock);
		emailVerificationRepository.saveAndFlush(EmailVerification.create(
			email, hmac(CORRECT_CODE), now.plusMinutes(5), now));
		return email;
	}

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
