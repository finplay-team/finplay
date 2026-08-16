// OAuth callback 요청의 state 쿠키를 소비하고 로그인·재인증 토큰 교환 리다이렉트, 교환 코드 소비를 처리한다.
package com.finplay.api.auth.controller;

import com.finplay.api.auth.dto.request.LoginExchangeRequest;
import com.finplay.api.auth.dto.request.ReauthExchangeRequest;
import com.finplay.api.auth.dto.response.ReauthTokenResponse;
import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.oauth.OAuthStateCookieFactory;
import com.finplay.api.auth.service.OAuthCallbackService;
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

	// 카카오·네이버 콘솔의 redirect_uri가 이 컨트롤러를 직접 가리켜 브라우저가 여기로 완전히 이동한다. LOGIN은
	// 최상위 이동이라 이 콜백이 곧 오프너다. REAUTH는 재인증 팝업이 여기로 이동하는데, 그 순간 팝업은 오프너와
	// 다른 오리진이 되어 SOP 때문에 오프너가 이 응답 본문을 직접 읽을 수 없다(spec 039). 그래서 두 purpose 모두
	// body로 토큰을 그대로 주지 않고 실제 토큰 없이 1회용 교환 코드만 실어 프론트 주소로 302 리다이렉트한다.
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

	// 위 리다이렉트가 실어 보낸 1회용 교환 코드를 실제 토큰으로 바꾼다. 코드는 발급 후 60초 안에 한 번만 쓸 수
	// 있다 — 두 번째 호출이나 만료된 코드는 VALIDATION_ERROR다(OAuthCallbackService.consumeLoginExchangeCode).
	@PostMapping("/login-exchange")
	public ResponseEntity<TokenResponse> exchange(@Valid @RequestBody
	LoginExchangeRequest request) {
		return ResponseEntity.ok(callbackService.consumeLoginExchangeCode(request.code()));
	}

	// REAUTH 콜백 리다이렉트가 실어 보낸 1회용 교환 코드를 실제 reauthToken으로 바꾼다. 규칙은 login-exchange와
	// 같다 — 두 번째 호출이나 만료된 코드는 VALIDATION_ERROR다(OAuthCallbackService.consumeReauthExchangeCode).
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
