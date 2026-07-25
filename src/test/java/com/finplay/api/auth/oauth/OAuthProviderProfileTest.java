// 실행 프로필에 따라 Fake 또는 실제 OAuth 공급자 빈만 활성화되는지 검증한다.
package com.finplay.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OAuthProviderProfileTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(
			FakeOAuthAuthorizationProvider.class,
			KakaoOAuthAuthorizationProvider.class,
			NaverOAuthAuthorizationProvider.class);

	@Test
	@DisplayName("기본 프로필에서는 실제 OAuth 키 없이 Fake 공급자 하나만 활성화된다")
	void defaultProfileWiresOnlyFakeProviderWithoutRealOAuthKeys() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(OAuthAuthorizationProvider.class);
			assertThat(context.getBean(OAuthAuthorizationProvider.class))
				.isInstanceOf(FakeOAuthAuthorizationProvider.class);
			assertThat(context).doesNotHaveBean(KakaoOAuthAuthorizationProvider.class);
			assertThat(context).doesNotHaveBean(NaverOAuthAuthorizationProvider.class);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"prod", "oauth-real"})
	@DisplayName("prod와 oauth-real 프로필에서는 실제 카카오와 네이버 공급자만 활성화된다")
	void realOAuthProfileWiresOnlyKakaoAndNaverProviders(String profile) {
		contextRunner
			.withPropertyValues(
				"spring.profiles.active=" + profile,
				"oauth.kakao.client-id=kakao-client-id",
				"oauth.kakao.redirect-uri=https://finplay.example/api/auth/oauth/kakao/callback",
				"oauth.naver.client-id=naver-client-id",
				"oauth.naver.redirect-uri=https://finplay.example/api/auth/oauth/naver/callback")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeansOfType(OAuthAuthorizationProvider.class))
					.hasSize(2)
					.containsOnlyKeys(
						"kakaoOAuthAuthorizationProvider", "naverOAuthAuthorizationProvider");
				assertThat(context).hasSingleBean(KakaoOAuthAuthorizationProvider.class);
				assertThat(context).hasSingleBean(NaverOAuthAuthorizationProvider.class);
				assertThat(context).doesNotHaveBean(FakeOAuthAuthorizationProvider.class);
			});
	}
}
