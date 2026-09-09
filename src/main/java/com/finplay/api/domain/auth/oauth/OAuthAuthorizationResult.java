package com.finplay.api.domain.auth.oauth;

import java.net.URI;

public record OAuthAuthorizationResult(
	OAuthProviderName provider,
	URI authorizationUri,
	String state) {
}
