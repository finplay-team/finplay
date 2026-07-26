// OAuth callback state를 검증한 뒤 공급자 조회와 FinPlay 로그인을 조정한다.
package com.finplay.api.auth.service;

import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.oauth.OAuthCallbackProvider;
import com.finplay.api.auth.oauth.OAuthProviderName;
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

	public TokenResponse callback(
		String rawProvider, String authorizationCode, String queryState, String cookieState) {
		OAuthProviderName provider = OAuthProviderName.from(rawProvider)
			.orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR));
		validateState(queryState, cookieState);
		if (authorizationCode == null || authorizationCode.isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}

		OAuthCallbackProvider callbackProvider = callbackProviders.stream()
			.filter(candidate -> candidate.supports(provider))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("활성화된 OAuth callback 공급자가 없습니다."));
		OAuthUserDto oauthUser = callbackProvider.fetchUser(authorizationCode, queryState);

		return authService.oauthLogin(provider, oauthUser);
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
}
