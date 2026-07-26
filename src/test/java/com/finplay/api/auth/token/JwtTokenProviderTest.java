// JWT 발급 결과의 클레임·만료 시간과 Access·Refresh Token 파싱 결과를 검증하는 단위 테스트다.
package com.finplay.api.auth.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class JwtTokenProviderTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-25T00:00:00Z");
	private static final String JWT_SECRET = "test-jwt-secret-that-is-at-least-32-bytes";
	private static final String OTHER_JWT_SECRET = "other-jwt-secret-that-is-at-least-32-bytes";
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

	@Test
	void issueCreatesDistinctAccessAndRefreshTokensForConsecutiveCallsWithFixedClock() {
		JwtTokenProvider provider = providerAt(FIXED_INSTANT, JWT_SECRET);

		IssuedTokenPair first = provider.issue(7L, "USER");
		IssuedTokenPair second = provider.issue(7L, "USER");

		assertThat(first.accessToken()).isNotEqualTo(second.accessToken());
		assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
	}

	@Test
	void issueRejectsNullUserId() {
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		JwtTokenProvider provider = new JwtTokenProvider(JWT_SECRET, ACCESS_TOKEN_EXPIRATION_MS,
			REFRESH_TOKEN_EXPIRATION_MS, fixedClock);

		assertThatIllegalArgumentException().isThrownBy(() -> provider.issue(null, "USER"));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", " "})
	void issueRejectsNullOrBlankRole(String role) {
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		JwtTokenProvider provider = new JwtTokenProvider(JWT_SECRET, ACCESS_TOKEN_EXPIRATION_MS,
			REFRESH_TOKEN_EXPIRATION_MS, fixedClock);

		assertThatIllegalArgumentException().isThrownBy(() -> provider.issue(7L, role));
	}

	@Test
	void parseAccessTokenReturnsUserIdAndRoleForValidAccessToken() {
		JwtTokenProvider provider = providerAt(FIXED_INSTANT, JWT_SECRET);

		String accessToken = provider.issue(7L, "USER").accessToken();

		assertThat(provider.parseAccessToken(accessToken)).contains(new AuthenticatedUser(7L, "USER"));
	}

	@Test
	void parseAccessTokenReturnsEmptyForRefreshToken() {
		JwtTokenProvider provider = providerAt(FIXED_INSTANT, JWT_SECRET);

		String refreshToken = provider.issue(7L, "USER").refreshToken();

		assertThat(provider.parseAccessToken(refreshToken)).isEmpty();
	}

	@Test
	void parseAccessTokenReturnsEmptyForExpiredToken() {
		JwtTokenProvider issuer = providerAt(FIXED_INSTANT, JWT_SECRET);
		String accessToken = issuer.issue(7L, "USER").accessToken();

		Instant afterExpiration = FIXED_INSTANT.plusMillis(ACCESS_TOKEN_EXPIRATION_MS).plusSeconds(1);
		JwtTokenProvider expiredClockProvider = providerAt(afterExpiration, JWT_SECRET);

		assertThat(issuer.parseAccessToken(accessToken)).isPresent();
		assertThat(expiredClockProvider.parseAccessToken(accessToken)).isEmpty();
	}

	@Test
	void parseAccessTokenReturnsEmptyForTamperedSignature() {
		JwtTokenProvider provider = providerAt(FIXED_INSTANT, JWT_SECRET);

		String tamperedToken = tamperSignature(provider.issue(7L, "USER").accessToken());

		assertThat(provider.parseAccessToken(tamperedToken)).isEmpty();
	}

	@Test
	void parseAccessTokenReturnsEmptyForOtherSecret() {
		JwtTokenProvider provider = providerAt(FIXED_INSTANT, JWT_SECRET);
		JwtTokenProvider otherSecretProvider = providerAt(FIXED_INSTANT, OTHER_JWT_SECRET);

		String foreignToken = otherSecretProvider.issue(7L, "USER").accessToken();

		assertThat(provider.parseAccessToken(foreignToken)).isEmpty();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"not-a-jwt", "", "   ", "a.b.c"})
	void parseAccessTokenReturnsEmptyForMalformedToken(String token) {
		JwtTokenProvider provider = providerAt(FIXED_INSTANT, JWT_SECRET);

		assertThat(provider.parseAccessToken(token)).isEmpty();
	}

	@Test
	void parseRefreshTokenReturnsUserForValidRefreshToken() {
		JwtTokenProvider provider = providerAt(FIXED_INSTANT, JWT_SECRET);

		String refreshToken = provider.issue(7L, "USER").refreshToken();

		assertThat(provider.parseRefreshToken(refreshToken)).contains(new AuthenticatedUser(7L, "USER"));
	}

	@Test
	void parseRefreshTokenReturnsEmptyForAccessToken() {
		JwtTokenProvider provider = providerAt(FIXED_INSTANT, JWT_SECRET);

		String accessToken = provider.issue(7L, "USER").accessToken();

		assertThat(provider.parseRefreshToken(accessToken)).isEmpty();
	}

	@Test
	void parseRefreshTokenReturnsEmptyForExpiredToken() {
		JwtTokenProvider issuer = providerAt(FIXED_INSTANT, JWT_SECRET);
		String refreshToken = issuer.issue(7L, "USER").refreshToken();

		Instant afterExpiration = FIXED_INSTANT.plusMillis(REFRESH_TOKEN_EXPIRATION_MS).plusSeconds(1);
		JwtTokenProvider expiredClockProvider = providerAt(afterExpiration, JWT_SECRET);

		assertThat(issuer.parseRefreshToken(refreshToken)).isPresent();
		assertThat(expiredClockProvider.parseRefreshToken(refreshToken)).isEmpty();
	}

	@Test
	void parseRefreshTokenReturnsEmptyForTamperedToken() {
		JwtTokenProvider provider = providerAt(FIXED_INSTANT, JWT_SECRET);

		String tamperedToken = tamperSignature(provider.issue(7L, "USER").refreshToken());

		assertThat(provider.parseRefreshToken(tamperedToken)).isEmpty();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"not-a-jwt", "", "   ", "a.b.c"})
	void parseRefreshTokenReturnsEmptyForMalformedNullOrBlankToken(String token) {
		JwtTokenProvider provider = providerAt(FIXED_INSTANT, JWT_SECRET);

		assertThat(provider.parseRefreshToken(token)).isEmpty();
	}

	private static JwtTokenProvider providerAt(Instant instant, String secret) {
		return new JwtTokenProvider(secret, ACCESS_TOKEN_EXPIRATION_MS, REFRESH_TOKEN_EXPIRATION_MS,
			Clock.fixed(instant, ZoneOffset.UTC));
	}

	private static String tamperSignature(String token) {
		int signatureStart = token.lastIndexOf('.') + 1;
		String signature = token.substring(signatureStart);
		char firstChar = signature.charAt(0);
		char replacement = firstChar == 'A' ? 'B' : 'A';
		return token.substring(0, signatureStart) + replacement + signature.substring(1);
	}
}
