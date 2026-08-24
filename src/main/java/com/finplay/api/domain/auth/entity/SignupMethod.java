// 회원의 가입 경로를 나타내는 응답 전용 파생 값 (별도 컬럼으로 영속하지 않는다)
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
