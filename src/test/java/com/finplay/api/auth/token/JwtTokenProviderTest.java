// JWT 발급 결과의 클레임과 만료 시간을 검증하는 단위 테스트다.
package com.finplay.api.auth.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class JwtTokenProviderTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-25T00:00:00Z");
	private static final String JWT_SECRET = "test-jwt-secret-that-is-at-least-32-bytes";
	private static final long ACCESS_TOKEN_EXPIRATION_MS = 3_600_000L;
	private static final long REFRESH_TOKEN_EXPIRATION_MS = 1_209_600_000L;

	@Test
	void issueCreatesSignedAccessAndRefreshTokensWithExpectedClaimsAndExpirations() {
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		JwtTokenProvider provider = new JwtTokenProvider(JWT_SECRET, ACCESS_TOKEN_EXPIRATION_MS,
				REFRESH_TOKEN_EXPIRATION_MS, fixedClock);

		IssuedTokenPair tokens = provider.issue(7L, "USER");

		SecretKey signingKey = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
		Claims accessClaims = Jwts.parser().clock(() -> Date.from(FIXED_INSTANT)).verifyWith(signingKey).build()
				.parseSignedClaims(tokens.accessToken())
				.getPayload();
		Claims refreshClaims = Jwts.parser().clock(() -> Date.from(FIXED_INSTANT)).verifyWith(signingKey).build()
				.parseSignedClaims(tokens.refreshToken())
				.getPayload();

		assertThat(accessClaims.getSubject()).isEqualTo("7");
		assertThat(accessClaims.get("role", String.class)).isEqualTo("USER");
		assertThat(accessClaims.get("tokenType", String.class)).isEqualTo("ACCESS");
		assertThat(refreshClaims.get("tokenType", String.class)).isEqualTo("REFRESH");
		assertThat(tokens.accessTokenExpiresInSeconds()).isEqualTo(3600L);
		assertThat(tokens.refreshTokenExpiresInSeconds()).isEqualTo(1_209_600L);
		assertThat(tokens.refreshTokenExpiresAt())
				.isEqualTo(LocalDateTime.ofInstant(FIXED_INSTANT.plusSeconds(1_209_600), ZoneOffset.UTC));
	}
}
