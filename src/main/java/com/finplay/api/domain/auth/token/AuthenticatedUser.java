package com.finplay.api.domain.auth.token;

public record AuthenticatedUser(Long userId, String role) {
}
