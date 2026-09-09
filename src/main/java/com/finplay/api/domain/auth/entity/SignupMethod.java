package com.finplay.api.domain.auth.entity;

import com.finplay.api.domain.auth.oauth.OAuthProviderName;

public enum SignupMethod {
	EMAIL,
	KAKAO,
	NAVER;

	public static SignupMethod fromProvider(OAuthProviderName provider) {
		return switch (provider) {
			case KAKAO -> KAKAO;
			case NAVER -> NAVER;
		};
	}
}
