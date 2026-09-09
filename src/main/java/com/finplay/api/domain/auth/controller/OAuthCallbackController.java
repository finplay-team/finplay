package com.finplay.api.domain.auth.controller;

import com.finplay.api.domain.auth.dto.request.LoginExchangeRequest;
import com.finplay.api.domain.auth.dto.request.ReauthExchangeRequest;
import com.finplay.api.domain.auth.dto.response.ReauthTokenResponse;
import com.finplay.api.domain.auth.dto.response.TokenResponse;
import com.finplay.api.domain.auth.oauth.state.OAuthStateCookieFactory;
import com.finplay.api.domain.auth.service.OAuthCallbackService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/auth/oauth")
public class OAuthCallbackController {

	private final OAuthCallbackService callbackService;
	private final OAuthStateCookieFactory stateCookieFactory;

	private final String loginRedirectUri;
	private final String reauthRedirectUri;

	public OAuthCallbackController(
		OAuthCallbackService callbackService,
		OAuthStateCookieFactory stateCookieFactory,
		@Value("${oauth.login-redirect-uri}")
		String loginRedirectUri,
		@Value("${oauth.reauth-redirect-uri}")
		String reauthRedirectUri) {
		this.callbackService = callbackService;
		this.stateCookieFactory = stateCookieFactory;
		this.loginRedirectUri = loginRedirectUri;
		this.reauthRedirectUri = reauthRedirectUri;
	}

	@GetMapping("/{provider}/callback")
	public ResponseEntity<Void> callback(
		@PathVariable
		String provider,
		@RequestParam(required = false)
		String code,
		@RequestParam(required = false)
		String state,
		@RequestParam(required = false)
		String error,
		@CookieValue(name = "oauth_state", required = false)
		String cookieState,
		HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, stateCookieFactory.expire(provider).toString());

		Object result = (error != null && !error.isBlank())
			? callbackService.callback(provider, code, state, cookieState, error)
			: callbackService.callback(provider, code, state, cookieState);

		if (result instanceof TokenResponse tokenResponse) {
			String exchangeCode = callbackService.issueLoginExchangeCode(tokenResponse);
			return redirectWithExchangeCode(loginRedirectUri, exchangeCode);
		}
		if (result instanceof ReauthTokenResponse reauthTokenResponse) {
			String exchangeCode = callbackService.issueReauthExchangeCode(reauthTokenResponse);
			return redirectWithExchangeCode(reauthRedirectUri, exchangeCode);
		}
		throw new IllegalStateException("알 수 없는 OAuth callback 결과 타입입니다: " + result.getClass());
	}

	@PostMapping("/login-exchange")
	public ResponseEntity<TokenResponse> exchange(@Valid @RequestBody
	LoginExchangeRequest request) {
		return ResponseEntity.ok(callbackService.consumeLoginExchangeCode(request.code()));
	}

	@PostMapping("/reauth-exchange")
	public ResponseEntity<ReauthTokenResponse> reauthExchange(@Valid @RequestBody
	ReauthExchangeRequest request) {
		return ResponseEntity.ok(callbackService.consumeReauthExchangeCode(request.code()));
	}

	private ResponseEntity<Void> redirectWithExchangeCode(String redirectUri, String exchangeCode) {
		URI location = UriComponentsBuilder.fromUriString(redirectUri)
			.queryParam("code", exchangeCode)
			.build()
			.toUri();
		return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
	}
}
