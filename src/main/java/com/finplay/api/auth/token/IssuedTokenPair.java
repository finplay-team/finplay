// Access·Refresh JWT 원문과 만료 정보를 함께 전달하는 내부 값 객체
package com.finplay.api.auth.token;

import java.time.LocalDateTime;

public record IssuedTokenPair(
	String accessToken,
	String refreshToken,
	LocalDateTime refreshTokenExpiresAt,
	long accessTokenExpiresInSeconds,
	long refreshTokenExpiresInSeconds) {
}
