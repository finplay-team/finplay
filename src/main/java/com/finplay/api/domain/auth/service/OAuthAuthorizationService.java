// OAuth 공급자를 선택하고 state와 인가 URI 생성을 조합한다.
package com.finplay.api.domain.auth.service;

import com.finplay.api.domain.auth.oauth.provider.OAuthAuthorizationProvider;
import com.finplay.api.domain.auth.oauth.OAuthAuthorizationResult;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.state.OAuthPurpose;
import com.finplay.api.domain.auth.oauth.state.OAuthStateGenerator;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OAuthAuthorizationService {

	private final List<OAuthAuthorizationProvider> authorizationProviders;
	private final OAuthStateGenerator stateGenerator;

	public OAuthAuthorizationResult authorize(String rawProvider) {
		return createAuthorization(rawProvider, OAuthPurpose.LOGIN, null);
	}

	public OAuthAuthorizationResult authorizeForReauth(String rawProvider, Long userId) {
		return createAuthorization(rawProvider, OAuthPurpose.REAUTH, userId);
	}

	private OAuthAuthorizationResult createAuthorization(
		String rawProvider, OAuthPurpose purpose, Long userId) {
		OAuthProviderName provider = OAuthProviderName.from(rawProvider)
			.orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR));
		OAuthAuthorizationProvider authorizationProvider = authorizationProviders.stream()
			.filter(candidate -> candidate.supports(provider))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("활성화된 OAuth 인가 공급자가 없습니다: " + provider));
		String state = stateGenerator.generate(purpose, userId);
		URI authorizationUri = authorizationProvider.createAuthorizationUri(provider, state);

		return new OAuthAuthorizationResult(provider, authorizationUri, state);
	}
}
