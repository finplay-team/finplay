// 로컬·테스트 프로필에서 외부 통신 없이 결정적인 OAuth 사용자 정보를 반환한다.
package com.finplay.api.auth.oauth;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!prod & !oauth-real")
public final class FakeOAuthCallbackProvider implements OAuthCallbackProvider {

	private static final String NO_EMAIL_CODE = "fake-code-no-email";
	private static final String EXISTING_EMAIL_CODE = "fake-code-existing-email";
	private static final String PROVIDER_USER_ID = "fake-oauth-user";
	private static final String DEFAULT_EMAIL = "fake-oauth@finplay.test";
	private static final String EXISTING_EMAIL = "existing-oauth@finplay.test";

	private final FakeOAuthGrantStore grantStore;

	public FakeOAuthCallbackProvider(FakeOAuthGrantStore grantStore) {
		this.grantStore = grantStore;
	}

	@Override
	public boolean supports(OAuthProviderName provider) {
		return provider == OAuthProviderName.KAKAO || provider == OAuthProviderName.NAVER;
	}

	@Override
	public OAuthUserDto fetchUser(String authorizationCode, String state) {
		return switch (authorizationCode) {
			case NO_EMAIL_CODE -> new OAuthUserDto(PROVIDER_USER_ID, null);
			case EXISTING_EMAIL_CODE -> new OAuthUserDto(PROVIDER_USER_ID, EXISTING_EMAIL);
			default -> fetchIssuedUser(authorizationCode, state);
		};
	}

	private OAuthUserDto fetchIssuedUser(String authorizationCode, String state) {
		if (!grantStore.consume(authorizationCode, state)) {
			throw new BusinessException(ErrorCode.OAUTH_AUTHORIZATION_FAILED);
		}
		return new OAuthUserDto(PROVIDER_USER_ID, DEFAULT_EMAIL);
	}
}
