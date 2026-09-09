package com.finplay.api.domain.auth.oauth.state;

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
