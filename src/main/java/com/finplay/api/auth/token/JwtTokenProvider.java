// 사용자 식별자와 역할을 서명된 Access·Refresh JWT 쌍으로 발급하는 컴포넌트
package com.finplay.api.auth.token;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class JwtTokenProvider {

	private static final String ROLE_CLAIM = "role";
	private static final String TOKEN_TYPE_CLAIM = "tokenType";
	private static final String ACCESS_TOKEN_TYPE = "ACCESS";
	private static final String REFRESH_TOKEN_TYPE = "REFRESH";

	private final SecretKey signingKey;
	private final long accessTokenExpirationMs;
	private final long refreshTokenExpirationMs;
	private final Clock clock;
	private final JwtParser accessTokenParser;

	public JwtTokenProvider(
		@Value("${jwt.secret}")
		String jwtSecret,
		@Value("${jwt.access-token-expiration-ms}")
		long accessTokenExpirationMs,
		@Value("${jwt.refresh-token-expiration-ms}")
		long refreshTokenExpirationMs,
		Clock clock) {
		this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
		this.accessTokenExpirationMs = accessTokenExpirationMs;
		this.refreshTokenExpirationMs = refreshTokenExpirationMs;
		this.clock = clock;
		this.accessTokenParser = Jwts.parser()
			.verifyWith(this.signingKey)
			.clock(() -> Date.from(clock.instant()))
			.build();
	}

	public Optional<AuthenticatedUser> parseAccessToken(String token) {
		try {
			Claims claims = accessTokenParser.parseSignedClaims(token).getPayload();
			if (!ACCESS_TOKEN_TYPE.equals(claims.get(TOKEN_TYPE_CLAIM, String.class))) {
				return Optional.empty();
			}
			Long userId = Long.valueOf(claims.getSubject());
			return Optional.of(new AuthenticatedUser(userId, claims.get(ROLE_CLAIM, String.class)));
		} catch (JwtException | IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	public IssuedTokenPair issue(Long userId, String role) {
		if (userId == null) {
			throw new IllegalArgumentException("사용자 ID는 필수입니다.");
		}
		if (role == null || role.isBlank()) {
			throw new IllegalArgumentException("역할은 필수입니다.");
		}

		Instant issuedAt = clock.instant();
		Instant accessTokenExpiresAt = issuedAt.plusMillis(accessTokenExpirationMs);
		Instant refreshTokenExpiresAt = issuedAt.plusMillis(refreshTokenExpirationMs);

		return new IssuedTokenPair(
			createToken(userId, role, ACCESS_TOKEN_TYPE, issuedAt, accessTokenExpiresAt),
			createToken(userId, role, REFRESH_TOKEN_TYPE, issuedAt, refreshTokenExpiresAt),
			LocalDateTime.ofInstant(refreshTokenExpiresAt, clock.getZone()),
			Duration.ofMillis(accessTokenExpirationMs).toSeconds(),
			Duration.ofMillis(refreshTokenExpirationMs).toSeconds());
	}

	private String createToken(Long userId, String role, String tokenType, Instant issuedAt, Instant expiresAt) {
		return Jwts.builder()
			.subject(String.valueOf(userId))
			.claim(ROLE_CLAIM, role)
			.claim(TOKEN_TYPE_CLAIM, tokenType)
			.issuedAt(Date.from(issuedAt))
			.expiration(Date.from(expiresAt))
			.signWith(signingKey)
			.compact();
	}
}
