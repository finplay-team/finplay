// 실제 애플리케이션 oauth-real 조립에서 RestClient와 실공급자 빈 구성을 통합 검증한다.
package com.finplay.api.domain.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.oauth.provider.FakeOAuthCallbackProvider;
import com.finplay.api.domain.auth.oauth.provider.KakaoOAuthCallbackProvider;
import com.finplay.api.domain.auth.oauth.provider.NaverOAuthCallbackProvider;
import com.finplay.api.domain.auth.oauth.provider.OAuthCallbackProvider;
import com.finplay.api.domain.auth.oauth.state.OAuthStateCookieFactory;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

@SpringBootTest(properties = {
	"oauth.state-cookie-secure=true",
	"oauth.kakao.client-id=test-kakao-client-id",
	"oauth.kakao.client-secret=test-kakao-client-secret",
	"oauth.kakao.redirect-uri=https://finplay.example/api/auth/oauth/kakao/callback",
	"oauth.naver.client-id=test-naver-client-id",
	"oauth.naver.client-secret=test-naver-client-secret",
	"oauth.naver.redirect-uri=https://finplay.example/api/auth/oauth/naver/callback"
})
@ActiveProfiles("oauth-real")
@Import(TestcontainersConfiguration.class)
class OAuthRealContextIntegrationTest {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private OAuthStateCookieFactory stateCookieFactory;

	@Test
	void oauthRealApplicationContextProvidesRestClientBuilderAndOnlyRealCallbackProviders() {
		assertThat(context.getBeansOfType(RestClient.Builder.class)).hasSize(1);
		Map<String, OAuthCallbackProvider> providers = context.getBeansOfType(OAuthCallbackProvider.class);
		assertThat(providers)
			.hasSize(2)
			.containsOnlyKeys(
				"kakaoOAuthCallbackProvider", "naverOAuthCallbackProvider");
		assertThat(context.getBeansOfType(FakeOAuthCallbackProvider.class)).isEmpty();
		assertThat(context.getBean(KakaoOAuthCallbackProvider.class)).isNotNull();
		assertThat(context.getBean(NaverOAuthCallbackProvider.class)).isNotNull();
		assertThat(stateCookieFactory.create(OAuthProviderName.KAKAO, "state").isSecure())
			.isTrue();
	}
}
