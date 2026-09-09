package com.finplay.api.domain.auth.oauth;

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
