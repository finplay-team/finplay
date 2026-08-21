// OAuth 인가 코드를 공급자 사용자 정보로 교환하는 callback 어댑터 계약이다.
package com.finplay.api.domain.auth.oauth.provider;

import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;

public interface OAuthCallbackProvider {

	boolean supports(OAuthProviderName provider);

	OAuthUserDto fetchUser(String authorizationCode, String state);
}
