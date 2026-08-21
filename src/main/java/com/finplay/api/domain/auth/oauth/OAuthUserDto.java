// OAuth 공급자가 반환한 회원 식별자와 이메일을 서비스 사이에 전달한다.
package com.finplay.api.domain.auth.oauth;

public record OAuthUserDto(String providerUserId, String email) {
}
