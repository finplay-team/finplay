// OAuth 공급자 경로 값을 지원 공급자 enum으로 해석한다.
package com.finplay.api.auth.oauth;

import java.util.Arrays;
import java.util.Optional;

public enum OAuthProviderName {
	KAKAO,
	NAVER;

	public static Optional<OAuthProviderName> from(String value) {
		if (value == null) {
			return Optional.empty();
		}

		return Arrays.stream(values()).filter(provider -> provider.name().equalsIgnoreCase(value)).findFirst();
	}
}
