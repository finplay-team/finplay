// OAuth state를 callback 경로에 한정된 10분 보안 쿠키로 만든다.
package com.finplay.api.auth.oauth;

import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class OAuthStateCookieFactory {

	private static final String COOKIE_NAME = "oauth_state";
	private static final String CALLBACK_PATH = "/api/auth/oauth/%s/callback";
	private static final Duration MAX_AGE = Duration.ofMinutes(10);
	private static final String SAME_SITE = "Lax";

	private final boolean secure;

	public OAuthStateCookieFactory(
		@Value("${oauth.state-cookie-secure:true}")
		boolean secure) {
		this.secure = secure;
	}

	public ResponseCookie create(OAuthProviderName provider, String state) {
		String path = CALLBACK_PATH.formatted(provider.name().toLowerCase(Locale.ROOT));

		return ResponseCookie.from(COOKIE_NAME, state)
			.httpOnly(true)
			.secure(secure)
			.sameSite(SAME_SITE)
			.path(path)
			.maxAge(MAX_AGE)
			.build();
	}
}
