// 재인증 성공 시 한 번만 노출하는 토큰 원문과 만료까지 남은 초를 담는 응답 DTO
package com.finplay.api.auth.dto.response;

public record ReauthTokenResponse(String reauthToken, long expiresInSeconds) {
}
