// 서명된 OAuth state에서 복원한 목적과 사용자 식별자를 담는다 (LOGIN이면 userId는 null).
package com.finplay.api.domain.auth.oauth.state;

public record OAuthStateClaims(OAuthPurpose purpose, Long userId) {
}
