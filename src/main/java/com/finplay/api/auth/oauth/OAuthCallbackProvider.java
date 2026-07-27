// OAuth 인가 코드를 공급자 사용자 정보로 교환하는 callback 어댑터 계약이다.
package com.finplay.api.auth.oauth;

public interface OAuthCallbackProvider {

	boolean supports(OAuthProviderName provider);

	OAuthUserDto fetchUser(String authorizationCode, String state);
}
