// 운영 프로필에서 카카오 OAuth 인가 URI를 생성한다.
package com.finplay.api.auth.oauth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Profile({"prod", "oauth-real"})
public class KakaoOAuthAuthorizationProvider implements OAuthAuthorizationProvider {

	private static final String AUTHORIZATION_ENDPOINT = "https://kauth.kakao.com/oauth/authorize";

	private final String clientId;
	private final String redirectUri;

	public KakaoOAuthAuthorizationProvider(
		@Value("${oauth.kakao.client-id}")
		String clientId,
		@Value("${oauth.kakao.redirect-uri}")
		String redirectUri) {
		this.clientId = clientId;
		this.redirectUri = redirectUri;
	}

	@Override
	public boolean supports(OAuthProviderName provider) {
		return provider == OAuthProviderName.KAKAO;
	}

	@Override
	public URI createAuthorizationUri(OAuthProviderName provider, String state) {
		return UriComponentsBuilder.fromUriString(AUTHORIZATION_ENDPOINT)
			.queryParam("response_type", "code")
			.queryParam("client_id", clientId)
			.queryParam("redirect_uri", redirectUri)
			.queryParam("state", state)
			.queryParam("scope", "account_email")
			.build()
			.encode(StandardCharsets.UTF_8)
			.toUri();
	}
}
