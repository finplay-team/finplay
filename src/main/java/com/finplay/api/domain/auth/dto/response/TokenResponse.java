package com.finplay.api.domain.auth.dto.response;

import com.finplay.api.domain.auth.token.IssuedTokenPair;

public record TokenResponse(
	String accessToken,
	String refreshToken,
	long accessTokenExpiresInSeconds,
	long refreshTokenExpiresInSeconds) {

	public static TokenResponse from(IssuedTokenPair tokens) {
		return new TokenResponse(
			tokens.accessToken(),
			tokens.refreshToken(),
			tokens.accessTokenExpiresInSeconds(),
			tokens.refreshTokenExpiresInSeconds());
	}
}
