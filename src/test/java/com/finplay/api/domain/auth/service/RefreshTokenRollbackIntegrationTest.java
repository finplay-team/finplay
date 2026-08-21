// 새 토큰 발급 실패 시 기존 Refresh Token 폐기가 실제 MySQL 트랜잭션에서 롤백되는지 검증하는 통합 테스트다.
package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.RefreshToken;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.dto.response.TokenResponse;
import com.finplay.api.domain.auth.repository.RefreshTokenRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.IssuedTokenPair;
import com.finplay.api.domain.auth.token.JwtTokenProvider;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RefreshTokenRollbackIntegrationTest {

	private static final String RAW_REFRESH_TOKEN = "rollback.refresh.jwt.token";
	private static final String ROTATED_ACCESS_TOKEN = "rotated.access.jwt.token";
	private static final String ROTATED_REFRESH_TOKEN = "rotated.refresh.jwt.token";

	@Autowired
	private AuthService authService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private Clock clock;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void issueFailureRollsBackRevocationAndAllowsSameTokenToRefreshLater() {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.saveAndFlush(User.create(
			uniqueEmail(), "password-hash", uniqueNickname(), now));
		RefreshToken original = refreshTokenRepository.saveAndFlush(RefreshToken.create(
			user, sha256(RAW_REFRESH_TOKEN), now.plusDays(14), now));
		when(jwtTokenProvider.parseRefreshToken(RAW_REFRESH_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(user.getId(), user.getRole())));
		IssuedTokenPair rotatedTokens = new IssuedTokenPair(
			ROTATED_ACCESS_TOKEN,
			ROTATED_REFRESH_TOKEN,
			now.plusDays(14),
			3600L,
			1_209_600L);
		when(jwtTokenProvider.issue(user.getId(), user.getRole()))
			.thenThrow(new IllegalStateException("token issue failed"))
			.thenReturn(rotatedTokens);

		assertThatThrownBy(() -> authService.refresh(RAW_REFRESH_TOKEN))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("token issue failed");

		TokenState afterFailure = loadStateInNewTransaction(original.getId());
		assertThat(afterFailure.revokedAt()).isNull();

		TokenResponse retried = authService.refresh(RAW_REFRESH_TOKEN);

		assertThat(retried.accessToken()).isEqualTo(ROTATED_ACCESS_TOKEN);
		assertThat(retried.refreshToken()).isEqualTo(ROTATED_REFRESH_TOKEN);
		TokenState afterRetry = loadStateInNewTransaction(original.getId());
		assertThat(afterRetry.revokedAt()).isNotNull();
		assertThat(refreshTokenRepository.findAllByTokenHash(sha256(ROTATED_REFRESH_TOKEN))).hasSize(1);
		assertThat(refreshTokenRepository.findAllByTokenHash(ROTATED_REFRESH_TOKEN)).isEmpty();
	}

	private TokenState loadStateInNewTransaction(Long tokenId) {
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return transactionTemplate.execute(status -> {
			RefreshToken token = refreshTokenRepository.findById(tokenId).orElseThrow();
			return new TokenState(token.getRevokedAt());
		});
	}

	private static String uniqueEmail() {
		return "rollback-" + UUID.randomUUID() + "@finplay.com";
	}

	private static String uniqueNickname() {
		return "rollback-" + UUID.randomUUID().toString().replace("-", "");
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private record TokenState(LocalDateTime revokedAt) {
	}
}
