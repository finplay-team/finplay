// 로컬·테스트 프로필에서 외부 통신 없이 가짜 OAuth callback URI를 생성한다.
package com.finplay.api.auth.oauth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Profile("!prod & !oauth-real")
public class FakeOAuthAuthorizationProvider implements OAuthAuthorizationProvider {

	private static final String CALLBACK_PATH = "/api/auth/oauth/{provider}/callback";
	private static final String FAKE_CODE = "fake-code";

	@Override
	public boolean supports(OAuthProviderName provider) {
		return provider == OAuthProviderName.KAKAO || provider == OAuthProviderName.NAVER;
	}

	@Override
	public URI createAuthorizationUri(OAuthProviderName provider, String state) {
		return UriComponentsBuilder.fromPath(CALLBACK_PATH)
			.queryParam("code", FAKE_CODE)
			.queryParam("state", state)
			.buildAndExpand(provider.name().toLowerCase(Locale.ROOT))
			.encode(StandardCharsets.UTF_8)
			.toUri();
	}
}
