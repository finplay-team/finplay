// OAuth 인가 요청 처리 결과의 공급자와 이동 URI 및 state를 전달한다.
package com.finplay.api.auth.oauth;

import java.net.URI;

public record OAuthAuthorizationResult(
	OAuthProviderName provider,
	URI authorizationUri,
	String state) {
}
