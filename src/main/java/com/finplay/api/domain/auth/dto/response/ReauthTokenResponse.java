package com.finplay.api.domain.auth.dto.response;

public record ReauthTokenResponse(String reauthToken, long expiresInSeconds) {
}
