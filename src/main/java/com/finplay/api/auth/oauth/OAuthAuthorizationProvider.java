// OAuth 공급자별 인가 URI 생성 구현이 따라야 하는 계약을 정의한다.
package com.finplay.api.auth.oauth;

import java.net.URI;

public interface OAuthAuthorizationProvider {

	boolean supports(OAuthProviderName provider);

	URI createAuthorizationUri(OAuthProviderName provider, String state);
}
