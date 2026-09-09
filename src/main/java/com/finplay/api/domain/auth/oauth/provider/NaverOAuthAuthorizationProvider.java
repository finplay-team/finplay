package com.finplay.api.domain.auth.oauth.provider;

import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Profile({"prod", "oauth-real"})
public final class NaverOAuthAuthorizationProvider implements OAuthAuthorizationProvider {

	private static final String AUTHORIZATION_ENDPOINT = "https://nid.naver.com/oauth2.0/authorize";

	private final String clientId;
	private final String redirectUri;

	public NaverOAuthAuthorizationProvider(
		@Value("${oauth.naver.client-id}")
		String clientId,
		@Value("${oauth.naver.redirect-uri}")
		String redirectUri) {
		this.clientId = requireConfigured(clientId, "네이버 OAuth clientId가 설정되지 않았습니다.");
		this.redirectUri = requireConfigured(redirectUri, "네이버 OAuth redirectUri가 설정되지 않았습니다.");
	}

	@Override
	public boolean supports(OAuthProviderName provider) {
		return provider == OAuthProviderName.NAVER;
	}

	@Override
	public URI createAuthorizationUri(OAuthProviderName provider, String state) {
		return UriComponentsBuilder.fromUriString(AUTHORIZATION_ENDPOINT)
			.queryParam("response_type", "code")
			.queryParam("client_id", "{clientId}")
			.queryParam("redirect_uri", "{redirectUri}")
			.queryParam("state", "{state}")
			.encode(StandardCharsets.UTF_8)
			.buildAndExpand(clientId, redirectUri, state)
			.toUri();
	}

	private String requireConfigured(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(message);
		}
		return value;
	}
}
