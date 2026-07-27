// OAuth callback state를 검증한 뒤 공급자 조회와 FinPlay 로그인을 조정한다.
package com.finplay.api.auth.service;

import com.finplay.api.auth.oauth.OAuthCallbackProvider;
import com.finplay.api.auth.oauth.OAuthProviderName;
import com.finplay.api.auth.oauth.OAuthStateClaims;
import com.finplay.api.auth.oauth.OAuthStateGenerator;
import com.finplay.api.auth.oauth.OAuthUserDto;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OAuthCallbackService {

	private final List<OAuthCallbackProvider> callbackProviders;
	private final AuthService authService;
	private final OAuthStateGenerator stateGenerator;

	// purpose는 서명된 state 안에만 있어 응답 타입을 컨트롤러 라우팅으로 나눌 수 없다.
	// Jackson은 선언 타입이 아니라 런타임 타입으로 직렬화하므로 Object 반환으로 분기한다.
	public Object callback(
		String rawProvider, String authorizationCode, String queryState, String cookieState) {
		return callback(rawProvider, authorizationCode, queryState, cookieState, null);
	}

	public Object callback(
		String rawProvider,
		String authorizationCode,
		String queryState,
		String cookieState,
		String authorizationError) {
		OAuthProviderName provider = OAuthProviderName.from(rawProvider)
			.orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR));
		validateState(queryState, cookieState);
		OAuthStateClaims claims = stateGenerator.verify(queryState);
		if (authorizationError != null && !authorizationError.isBlank()) {
			throw new BusinessException(ErrorCode.OAUTH_AUTHORIZATION_FAILED);
		}
		if (authorizationCode == null || authorizationCode.isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}

		OAuthCallbackProvider callbackProvider = callbackProviders.stream()
			.filter(candidate -> candidate.supports(provider))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("활성화된 OAuth callback 공급자가 없습니다."));
		OAuthUserDto oauthUser = callbackProvider.fetchUser(authorizationCode, queryState);
		validateOAuthUser(oauthUser);

		return switch (claims.purpose()) {
			case LOGIN -> authService.oauthLogin(provider, oauthUser);
			case REAUTH -> authService.reauthenticate(claims.userId(), provider, oauthUser);
		};
	}

	private void validateState(String queryState, String cookieState) {
		if (queryState == null || queryState.isBlank() || cookieState == null || cookieState.isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}

		byte[] queryStateBytes = queryState.getBytes(StandardCharsets.UTF_8);
		byte[] cookieStateBytes = cookieState.getBytes(StandardCharsets.UTF_8);
		if (!MessageDigest.isEqual(queryStateBytes, cookieStateBytes)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	private void validateOAuthUser(OAuthUserDto oauthUser) {
		if (oauthUser == null
			|| oauthUser.providerUserId() == null
			|| oauthUser.providerUserId().isBlank()) {
			throw new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
		}
		if (oauthUser.email() == null || oauthUser.email().isBlank()) {
			throw new BusinessException(ErrorCode.OAUTH_EMAIL_REQUIRED);
		}
	}
}
