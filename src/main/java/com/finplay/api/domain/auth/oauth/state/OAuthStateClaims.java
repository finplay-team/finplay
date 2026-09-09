package com.finplay.api.domain.auth.oauth.state;

public record OAuthStateClaims(OAuthPurpose purpose, Long userId) {
}
