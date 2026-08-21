// Access Token에서 파싱한 인증 주체(사용자 식별자·역할)를 담는 값 객체
package com.finplay.api.domain.auth.token;

public record AuthenticatedUser(Long userId, String role) {
}
