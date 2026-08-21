// 실제 MySQL에서 Refresh Token 로그아웃의 폐기 상태와 사용자·세션 소유권을 검증하는 통합 테스트다.
package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.RefreshToken;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.dto.response.TokenResponse;
import com.finplay.api.domain.auth.repository.RefreshTokenRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LogoutIntegrationTest {

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
	private Clock clock;

	@Test
	void logoutRevokesSubmittedTokenAndRejectsItsRefreshReuse() {
		User user = persistUser("revoke");
		TokenResponse loginTokens = authService.login(user.getEmail(), PASSWORD);

		authService.logout(user.getId(), loginTokens.refreshToken());

		assertThat(findStoredToken(loginTokens.refreshToken()).getRevokedAt()).isNotNull();
		BusinessException reuseFailure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.refresh(loginTokens.refreshToken()));
		assertThat(reuseFailure.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
	}

	@Test
	void logoutRejectsAnotherUsersTokenWithoutRevokingItAndOwnerCanRefresh() {
		User accessTokenUser = persistUser("access-owner");
		User refreshTokenUser = persistUser("refresh-owner");
		authService.login(accessTokenUser.getEmail(), PASSWORD);
		TokenResponse refreshOwnerLogin = authService.login(refreshTokenUser.getEmail(), PASSWORD);

		BusinessException ownershipFailure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.logout(accessTokenUser.getId(), refreshOwnerLogin.refreshToken()));

		assertThat(ownershipFailure.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN);
		assertThat(findStoredToken(refreshOwnerLogin.refreshToken()).getRevokedAt()).isNull();

		TokenResponse rotated = authService.refresh(refreshOwnerLogin.refreshToken());

		assertThat(rotated.accessToken()).isNotBlank();
		assertThat(rotated.refreshToken()).isNotBlank();
		assertThat(findStoredToken(refreshOwnerLogin.refreshToken()).getRevokedAt()).isNotNull();
		assertThat(findStoredToken(rotated.refreshToken()).getRevokedAt()).isNull();
	}

	@Test
	void logoutRevokesOnlySubmittedSessionAndOtherSessionCanRefresh() {
		User user = persistUser("multiple-sessions");
		TokenResponse submittedSession = authService.login(user.getEmail(), PASSWORD);
		TokenResponse otherSession = authService.login(user.getEmail(), PASSWORD);

		authService.logout(user.getId(), submittedSession.refreshToken());

		assertThat(findStoredToken(submittedSession.refreshToken()).getRevokedAt()).isNotNull();
		assertThat(findStoredToken(otherSession.refreshToken()).getRevokedAt()).isNull();

		TokenResponse rotatedOtherSession = authService.refresh(otherSession.refreshToken());

		assertThat(rotatedOtherSession.refreshToken()).isNotBlank();
		assertThat(findStoredToken(otherSession.refreshToken()).getRevokedAt()).isNotNull();
		assertThat(findStoredToken(rotatedOtherSession.refreshToken()).getRevokedAt()).isNull();
	}

	private User persistUser(String scenario) {
		LocalDateTime now = LocalDateTime.now(clock);
		return userRepository.saveAndFlush(User.create(
			uniqueEmail(scenario), passwordEncoder.encode(PASSWORD), uniqueNickname(scenario), now));
	}

	private RefreshToken findStoredToken(String rawRefreshToken) {
		return refreshTokenRepository.findAllByTokenHash(sha256(rawRefreshToken))
			.stream()
			.findFirst()
			.orElseThrow();
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
}
