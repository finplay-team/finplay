package com.finplay.api.domain.auth.oauth.provider;

import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;

public interface OAuthCallbackProvider {

	boolean supports(OAuthProviderName provider);

	OAuthUserDto fetchUser(String authorizationCode, String state);
}
