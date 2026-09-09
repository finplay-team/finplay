package com.finplay.api.domain.auth.service;

import com.finplay.api.domain.auth.dto.response.ReauthTokenResponse;
import com.finplay.api.domain.auth.dto.response.TokenResponse;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
import com.finplay.api.domain.auth.oauth.exchange.OAuthLoginExchangeStore;
import com.finplay.api.domain.auth.oauth.exchange.OAuthReauthExchangeStore;
import com.finplay.api.domain.auth.oauth.provider.OAuthCallbackProvider;
import com.finplay.api.domain.auth.oauth.state.OAuthPurpose;
import com.finplay.api.domain.auth.oauth.state.OAuthStateClaims;
import com.finplay.api.domain.auth.oauth.state.OAuthStateGenerator;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
	private final OAuthLoginExchangeStore exchangeStore;
	private final OAuthReauthExchangeStore reauthExchangeStore;

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
		requireQueryState(queryState);
		OAuthStateClaims claims = verifyState(queryState, cookieState);
		if (claims.purpose() == OAuthPurpose.LOGIN) {
			validateCookieState(queryState, cookieState);
		}
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

	public String issueLoginExchangeCode(TokenResponse tokens) {
		return exchangeStore.issue(tokens);
	}

	public TokenResponse consumeLoginExchangeCode(String code) {
		return exchangeStore.consume(code).orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR));
	}

	public String issueReauthExchangeCode(ReauthTokenResponse reauthToken) {
		return reauthExchangeStore.issue(reauthToken);
	}

	public ReauthTokenResponse consumeReauthExchangeCode(String code) {
		return reauthExchangeStore.consume(code)
			.orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR));
	}

	private void requireQueryState(String queryState) {
		if (queryState == null || queryState.isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	private OAuthStateClaims verifyState(String queryState, String cookieState) {
		try {
			return stateGenerator.verify(queryState);
		} catch (BusinessException ex) {
			if (!matchesQueryState(queryState, cookieState)) {
				throw new BusinessException(ErrorCode.VALIDATION_ERROR);
			}
			throw ex;
		}
	}

	private void validateCookieState(String queryState, String cookieState) {
		if (!matchesQueryState(queryState, cookieState)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	private boolean matchesQueryState(String queryState, String cookieState) {
		if (cookieState == null || cookieState.isBlank()) {
			return false;
		}

		byte[] queryStateBytes = queryState.getBytes(StandardCharsets.UTF_8);
		byte[] cookieStateBytes = cookieState.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(queryStateBytes, cookieStateBytes);
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
