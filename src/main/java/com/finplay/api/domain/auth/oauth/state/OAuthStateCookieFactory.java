package com.finplay.api.domain.auth.oauth.state;

import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public final class OAuthStateCookieFactory {

	private static final String COOKIE_NAME = "oauth_state";
	private static final String CALLBACK_PATH = "/api/auth/oauth/%s/callback";
	private static final String OAUTH_PATH = "/api/auth/oauth";
	private static final Duration MAX_AGE = Duration.ofMinutes(10);
	private static final String SAME_SITE = "Lax";
	private static final Duration EXPIRED_MAX_AGE = Duration.ZERO;
	private static final Profiles REAL_OAUTH_PROFILES = Profiles.of("prod | oauth-real");

	private final boolean secure;

	@Autowired
	OAuthStateCookieFactory(
		@Value("${oauth.state-cookie-secure:true}")
		boolean secure,
		Environment environment) {
		this(secure, environment.acceptsProfiles(REAL_OAUTH_PROFILES));
	}

	OAuthStateCookieFactory(boolean secure) {
		this(secure, false);
	}

	private OAuthStateCookieFactory(boolean secure, boolean isRealOAuthProfile) {
		if (isRealOAuthProfile && !secure) {
			throw new IllegalStateException("prod 또는 oauth-real 프로필에서는 OAuth state 쿠키의 Secure 속성을 끌 수 없습니다.");
		}
		this.secure = secure;
	}

	public ResponseCookie create(OAuthProviderName provider, String state) {
		return ResponseCookie.from(COOKIE_NAME, state)
			.httpOnly(true)
			.secure(secure)
			.sameSite(SAME_SITE)
			.path(callbackPath(provider))
			.maxAge(MAX_AGE)
			.build();
	}

	public ResponseCookie expire(OAuthProviderName provider) {
		return buildExpiredCookie(callbackPath(provider));
	}

	public ResponseCookie expire(String rawProvider) {
		String path = OAuthProviderName.from(rawProvider)
			.map(this::callbackPath)
			.orElse(OAUTH_PATH);
		return buildExpiredCookie(path);
	}

	private ResponseCookie buildExpiredCookie(String path) {
		return ResponseCookie.from(COOKIE_NAME, "")
			.httpOnly(true)
			.secure(secure)
			.sameSite(SAME_SITE)
			.path(path)
			.maxAge(EXPIRED_MAX_AGE)
			.build();
	}

	private String callbackPath(OAuthProviderName provider) {
		return CALLBACK_PATH.formatted(provider.name().toLowerCase(Locale.ROOT));
	}
}
