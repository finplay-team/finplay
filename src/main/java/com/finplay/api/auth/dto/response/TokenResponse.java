// 회원가입 성공 시 Access·Refresh JWT와 만료 초를 반환하는 응답 DTO
package com.finplay.api.auth.dto.response;

import com.finplay.api.auth.token.IssuedTokenPair;

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
