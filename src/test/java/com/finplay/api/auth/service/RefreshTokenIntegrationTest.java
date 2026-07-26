// 로그인부터 Refresh Token 회전·재사용 거부·동시 소비까지 실제 MySQL로 검증하는 통합 테스트다.
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.RefreshToken;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.repository.RefreshTokenRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RefreshTokenIntegrationTest {

	private static final String PASSWORD = "password123";

	@Autowired
	private AuthService authService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock clock;

	@Test
	void loginRefreshRejectsPreviousTokenAndAllowsRotatedTokenToRotateAgain() {
		User user = persistUser("rotation");
		TokenResponse loginTokens = authService.login(user.getEmail(), PASSWORD);

		TokenResponse firstRotation = authService.refresh(loginTokens.refreshToken());
		BusinessException reusedTokenFailure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.refresh(loginTokens.refreshToken()));
		TokenResponse secondRotation = authService.refresh(firstRotation.refreshToken());

		assertThat(reusedTokenFailure.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
		assertThat(firstRotation.refreshToken()).isNotEqualTo(loginTokens.refreshToken());
		assertThat(secondRotation.refreshToken()).isNotEqualTo(firstRotation.refreshToken());
		assertThat(refreshTokenRepository.findAllByTokenHash(sha256(loginTokens.refreshToken())))
			.singleElement()
			.extracting(RefreshToken::getRevokedAt)
			.isNotNull();
		assertThat(refreshTokenRepository.findAllByTokenHash(sha256(firstRotation.refreshToken())))
			.singleElement()
			.extracting(RefreshToken::getRevokedAt)
			.isNotNull();
		assertThat(refreshTokenRepository.findAllByTokenHash(sha256(secondRotation.refreshToken())))
			.singleElement()
			.extracting(RefreshToken::getRevokedAt)
			.isNull();
		assertThat(refreshTokenRepository.findAllByTokenHash(secondRotation.refreshToken())).isEmpty();
		assertThat(countRefreshTokens(user.getId(), false)).isEqualTo(3);
		assertThat(countRefreshTokens(user.getId(), true)).isEqualTo(1);
	}

	@Test
	void concurrentRefreshAllowsExactlyOneSuccessAndCreatesOneActiveToken() throws Exception {
		User user = persistUser("concurrent");
		TokenResponse loginTokens = authService.login(user.getEmail(), PASSWORD);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Callable<RefreshAttempt> refresh = () -> {
			ready.countDown();
			if (!start.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("concurrent refresh start timeout");
			}
			try {
				return RefreshAttempt.succeeded(authService.refresh(loginTokens.refreshToken()));
			} catch (BusinessException exception) {
				return RefreshAttempt.failed(exception.getErrorCode());
			}
		};

		List<RefreshAttempt> attempts;
		try {
			Future<RefreshAttempt> first = executor.submit(refresh);
			Future<RefreshAttempt> second = executor.submit(refresh);
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			attempts = List.of(
				first.get(30, TimeUnit.SECONDS),
				second.get(30, TimeUnit.SECONDS));
		} finally {
			start.countDown();
			executor.shutdownNow();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}

		assertThat(attempts).filteredOn(RefreshAttempt::success).hasSize(1);
		assertThat(attempts)
			.filteredOn(attempt -> !attempt.success())
			.extracting(RefreshAttempt::errorCode)
			.containsExactly(ErrorCode.UNAUTHORIZED);
		TokenResponse rotated = attempts.stream()
			.filter(RefreshAttempt::success)
			.map(RefreshAttempt::response)
			.findFirst()
			.orElseThrow();
		assertThat(refreshTokenRepository.findAllByTokenHash(sha256(rotated.refreshToken()))).hasSize(1);
		assertThat(countRefreshTokens(user.getId(), false)).isEqualTo(2);
		assertThat(countRefreshTokens(user.getId(), true)).isEqualTo(1);
	}

	private User persistUser(String scenario) {
		LocalDateTime now = LocalDateTime.now(clock);
		return userRepository.saveAndFlush(User.create(
			uniqueEmail(scenario), passwordEncoder.encode(PASSWORD), uniqueNickname(scenario), now));
	}

	private int countRefreshTokens(Long userId, boolean activeOnly) {
		String activeClause = activeOnly ? " and revoked_at is null" : "";
		Integer count = jdbcTemplate.queryForObject(
			"select count(*) from refresh_tokens where user_id = ?" + activeClause,
			Integer.class,
			userId);
		return count == null ? 0 : count;
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "");
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private record RefreshAttempt(boolean success, TokenResponse response, ErrorCode errorCode) {

		private static RefreshAttempt succeeded(TokenResponse response) {
			return new RefreshAttempt(true, response, null);
		}

		private static RefreshAttempt failed(ErrorCode errorCode) {
			return new RefreshAttempt(false, null, errorCode);
		}
	}
}
