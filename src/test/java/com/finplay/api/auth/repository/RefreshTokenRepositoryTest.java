// Refresh Token 해시 조회와 활성 토큰의 원자적 단일 폐기를 실제 MySQL로 검증하는 슬라이스 테스트다.
package com.finplay.api.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.RefreshToken;
import com.finplay.api.auth.domain.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class RefreshTokenRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 26, 10, 30, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void findAllByTokenHashReturnsMatchingRowsWithoutRawTokenLookup() {
		String rawToken = "raw-refresh-token";
		String tokenHash = sha256(rawToken);
		User user = saveUser("hash@finplay.com", "hash-user");
		refreshTokenRepository.saveAll(List.of(
			RefreshToken.create(user, tokenHash, NOW.plusDays(14), NOW),
			RefreshToken.create(user, tokenHash, NOW.plusDays(14), NOW),
			RefreshToken.create(user, sha256("other-refresh-token"), NOW.plusDays(14), NOW)));
		refreshTokenRepository.flush();
		entityManager.clear();

		List<RefreshToken> foundByHash = refreshTokenRepository.findAllByTokenHash(tokenHash);
		List<RefreshToken> foundByRawToken = refreshTokenRepository.findAllByTokenHash(rawToken);

		assertThat(foundByHash).hasSize(2).allSatisfy(token -> assertThat(token.getTokenHash()).isEqualTo(tokenHash));
		assertThat(foundByRawToken).isEmpty();
	}

	@Test
	void revokeIfActiveAndNotExpiredRevokesValidTokenOnlyOnce() {
		User user = saveUser("active@finplay.com", "active-user");
		RefreshToken token = refreshTokenRepository.saveAndFlush(
			RefreshToken.create(user, sha256("active-refresh-token"), NOW.plusDays(14), NOW));

		assertThat(refreshTokenRepository.revokeIfActiveAndNotExpired(token.getId(), NOW)).isEqualTo(1);
		assertThat(refreshTokenRepository.revokeIfActiveAndNotExpired(token.getId(), NOW.plusSeconds(1))).isZero();

		entityManager.clear();
		RefreshToken revoked = refreshTokenRepository.findById(token.getId()).orElseThrow();
		assertThat(revoked.getRevokedAt()).isEqualTo(NOW);
	}

	@Test
	void revokeIfActiveAndNotExpiredRejectsAlreadyRevokedToken() {
		User user = saveUser("revoked@finplay.com", "revoked-user");
		RefreshToken token = RefreshToken.create(
			user, sha256("revoked-refresh-token"), NOW.plusDays(14), NOW.minusDays(1));
		ReflectionTestUtils.setField(token, "revokedAt", NOW.minusHours(1));
		refreshTokenRepository.saveAndFlush(token);

		assertThat(refreshTokenRepository.revokeIfActiveAndNotExpired(token.getId(), NOW)).isZero();
	}

	@Test
	void revokeIfActiveAndNotExpiredRejectsTokenExpiredAtOrBeforeNow() {
		User user = saveUser("expired@finplay.com", "expired-user");
		RefreshToken expiresNow = refreshTokenRepository.save(
			RefreshToken.create(user, sha256("expires-now-refresh-token"), NOW, NOW.minusDays(1)));
		RefreshToken expired = refreshTokenRepository.saveAndFlush(
			RefreshToken.create(user, sha256("expired-refresh-token"), NOW.minusSeconds(1), NOW.minusDays(1)));

		assertThat(refreshTokenRepository.revokeIfActiveAndNotExpired(expiresNow.getId(), NOW)).isZero();
		assertThat(refreshTokenRepository.revokeIfActiveAndNotExpired(expired.getId(), NOW)).isZero();
	}

	@Test
	void revokeAllActiveByUserIdRevokesOnlyTargetUsersActiveTokens() {
		User user = saveUser("bulk-revoke@finplay.com", "bulk-revoke-user");
		User otherUser = saveUser("bulk-revoke-other@finplay.com", "bulk-revoke-other-user");

		RefreshToken active1 = refreshTokenRepository.save(
			RefreshToken.create(user, sha256("bulk-active-1"), NOW.plusDays(14), NOW));
		RefreshToken active2 = refreshTokenRepository.save(
			RefreshToken.create(user, sha256("bulk-active-2"), NOW.plusDays(14), NOW));

		RefreshToken alreadyRevoked = RefreshToken.create(user, sha256("bulk-already-revoked"), NOW.plusDays(14), NOW);
		ReflectionTestUtils.setField(alreadyRevoked, "revokedAt", NOW.minusHours(1));
		refreshTokenRepository.save(alreadyRevoked);

		// 이미 만료됐지만 아직 폐기되지 않은 토큰 — 만료 여부는 조건이 아니므로 폐기 대상에 포함되어야 한다.
		RefreshToken expiredNotRevoked = refreshTokenRepository.save(
			RefreshToken.create(user, sha256("bulk-expired-not-revoked"), NOW.minusDays(1), NOW.minusDays(15)));

		RefreshToken otherUserActive = refreshTokenRepository.save(
			RefreshToken.create(otherUser, sha256("bulk-other-active"), NOW.plusDays(14), NOW));
		refreshTokenRepository.flush();

		int revokedCount = refreshTokenRepository.revokeAllActiveByUserId(user.getId(), NOW.plusMinutes(1));

		assertThat(revokedCount).isEqualTo(3);
		entityManager.clear();
		assertThat(refreshTokenRepository.findById(active1.getId()).orElseThrow().getRevokedAt())
			.isEqualTo(NOW.plusMinutes(1));
		assertThat(refreshTokenRepository.findById(active2.getId()).orElseThrow().getRevokedAt())
			.isEqualTo(NOW.plusMinutes(1));
		assertThat(refreshTokenRepository.findById(expiredNotRevoked.getId()).orElseThrow().getRevokedAt())
			.isEqualTo(NOW.plusMinutes(1));
		assertThat(refreshTokenRepository.findById(alreadyRevoked.getId()).orElseThrow().getRevokedAt())
			.isEqualTo(NOW.minusHours(1));
		assertThat(refreshTokenRepository.findById(otherUserActive.getId()).orElseThrow().getRevokedAt())
			.isNull();
	}

	private User saveUser(String email, String nickname) {
		return userRepository.saveAndFlush(User.create(email, "password-hash", nickname, NOW));
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
