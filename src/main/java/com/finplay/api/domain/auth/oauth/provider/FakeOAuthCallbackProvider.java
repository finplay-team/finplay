package com.finplay.api.domain.auth.oauth.provider;

import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
import com.finplay.api.domain.auth.oauth.exchange.FakeOAuthGrantStore;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

public class FakeOAuthCallbackProvider implements OAuthCallbackProvider {

	private static final String NO_EMAIL_CODE = "fake-code-no-email";
	private static final String EXISTING_EMAIL_CODE = "fake-code-existing-email";
	private static final String PROVIDER_USER_ID = "fake-oauth-user";
	private static final String DEFAULT_EMAIL = "fake-oauth@finplay.test";
	private static final String EXISTING_EMAIL = "existing-oauth@finplay.test";

	private final FakeOAuthGrantStore grantStore;
	private final OAuthProviderName provider;

	public FakeOAuthCallbackProvider(FakeOAuthGrantStore grantStore, OAuthProviderName provider) {
		this.grantStore = grantStore;
		this.provider = provider;
	}

	@Override
	public boolean supports(OAuthProviderName candidate) {
		return provider == candidate;
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
		if (!grantStore.consume(provider, authorizationCode, state)) {
			throw new BusinessException(ErrorCode.OAUTH_AUTHORIZATION_FAILED);
		}
		return new OAuthUserDto(PROVIDER_USER_ID, DEFAULT_EMAIL);
	}
}

@Component
@Profile("!prod & !oauth-real")
final class FakeKakaoOAuthCallbackProvider extends FakeOAuthCallbackProvider {

	FakeKakaoOAuthCallbackProvider(FakeOAuthGrantStore grantStore) {
		super(grantStore, OAuthProviderName.KAKAO);
	}
}

@Component
@Profile("!prod & !oauth-real")
final class FakeNaverOAuthCallbackProvider extends FakeOAuthCallbackProvider {

	FakeNaverOAuthCallbackProvider(FakeOAuthGrantStore grantStore) {
		super(grantStore, OAuthProviderName.NAVER);
	}
}
