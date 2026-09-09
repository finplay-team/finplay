package com.finplay.api.domain.auth.dto.response;

public record SignupTokenResponse(String signupVerificationToken, long expiresInSeconds) {
}
