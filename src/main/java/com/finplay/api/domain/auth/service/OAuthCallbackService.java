// OAuth callback state를 검증한 뒤 공급자 조회와 FinPlay 로그인·재인증, 교환 코드 발급·소비를 조정한다.
package com.finplay.api.domain.auth.service;

import com.finplay.api.domain.auth.dto.response.ReauthTokenResponse;
import com.finplay.api.domain.auth.dto.response.TokenResponse;
import com.finplay.api.domain.auth.oauth.provider.OAuthCallbackProvider;
import com.finplay.api.domain.auth.oauth.exchange.OAuthLoginExchangeStore;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.state.OAuthPurpose;
import com.finplay.api.domain.auth.oauth.exchange.OAuthReauthExchangeStore;
import com.finplay.api.domain.auth.oauth.state.OAuthStateClaims;
import com.finplay.api.domain.auth.oauth.state.OAuthStateGenerator;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
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
		requireQueryState(queryState);
		OAuthStateClaims claims = verifyState(queryState, cookieState);
		// REAUTH는 쿠키 이중제출에 의존하지 않는다 — state 자체 만료시각과 AuthService.reauthenticate()의
		// provider+providerUserId 검증이 CSRF·재생 방어를 대신한다 (spec 039).
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

	/** LOGIN 콜백이 발급한 토큰을 1회용 교환 코드로 감싼다. Redis 접촉은 {@link OAuthLoginExchangeStore}에 맡긴다. */
	public String issueLoginExchangeCode(TokenResponse tokens) {
		return exchangeStore.issue(tokens);
	}

	/** 교환 코드를 실제 토큰으로 바꾼다. 이미 소비됐거나 만료됐거나 존재하지 않으면 400 VALIDATION_ERROR다. */
	public TokenResponse consumeLoginExchangeCode(String code) {
		return exchangeStore.consume(code).orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR));
	}

	/** REAUTH 콜백이 발급한 reauthToken을 1회용 교환 코드로 감싼다. Redis 접촉은 {@link OAuthReauthExchangeStore}에 맡긴다. */
	public String issueReauthExchangeCode(ReauthTokenResponse reauthToken) {
		return reauthExchangeStore.issue(reauthToken);
	}

	/** 교환 코드를 실제 reauthToken으로 바꾼다. 이미 소비됐거나 만료됐거나 존재하지 않으면 400 VALIDATION_ERROR다. */
	public ReauthTokenResponse consumeReauthExchangeCode(String code) {
		return reauthExchangeStore.consume(code)
			.orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR));
	}

	private void requireQueryState(String queryState) {
		if (queryState == null || queryState.isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	/**
	 * 서명·purpose·필드개수·만료 검증에 실패하면(purpose를 아직 모르는 상황) 쿠키-쿼리 불일치 자체로도 거부
	 * 사유가 이미 성립한다 — 그 경우 재인증 실패(403)보다 검증 오류(400)를 우선한다. 쿠키가 쿼리 state와
	 * 정확히 같을 때만 원래의 재인증 실패를 그대로 드러낸다(LOGIN·REAUTH 공통, purpose 판별 이전 규칙).
	 */
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
