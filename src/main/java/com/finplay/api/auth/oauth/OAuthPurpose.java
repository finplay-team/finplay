// OAuth 인가 요청의 목적(로그인/재인증)을 나타내는 열거형
package com.finplay.api.auth.oauth;

import java.util.Arrays;
import java.util.Optional;

public enum OAuthPurpose {
	LOGIN,
	REAUTH;

	public static Optional<OAuthPurpose> from(String value) {
		if (value == null) {
			return Optional.empty();
		}

		return Arrays.stream(values()).filter(purpose -> purpose.name().equalsIgnoreCase(value)).findFirst();
	}
}
