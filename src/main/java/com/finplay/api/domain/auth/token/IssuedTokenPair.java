package com.finplay.api.domain.auth.token;

import java.time.LocalDateTime;

public record IssuedTokenPair(
	String accessToken,
	String refreshToken,
	LocalDateTime refreshTokenExpiresAt,
	long accessTokenExpiresInSeconds,
	long refreshTokenExpiresInSeconds) {
}
