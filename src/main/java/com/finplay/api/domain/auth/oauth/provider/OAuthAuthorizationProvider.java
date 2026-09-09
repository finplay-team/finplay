package com.finplay.api.domain.auth.oauth.provider;

import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import java.net.URI;

public interface OAuthAuthorizationProvider {

	boolean supports(OAuthProviderName provider);

	URI createAuthorizationUri(OAuthProviderName provider, String state);
}
