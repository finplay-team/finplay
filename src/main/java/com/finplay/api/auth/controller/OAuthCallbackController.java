// OAuth callback 요청의 state 쿠키를 소비하고 로그인 토큰 교환 리다이렉트 또는 재인증 토큰 응답을 반환한다.
package com.finplay.api.auth.controller;

import com.finplay.api.auth.dto.request.LoginExchangeRequest;
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

	// 카카오·네이버 콘솔의 redirect_uri가 이 컨트롤러를 직접 가리켜 브라우저가 여기로 완전히 이동한다. 그래서
	// LOGIN 성공은 body로 토큰을 주지 않고 이 프론트 주소로 302 리다이렉트한다 — JSON을 그대로 주면 사용자
	// 눈에 API 응답 화면이 뜨고 프론트는 로그인 사실 자체를 모른다(REAUTH는 재인증 SPA가 이미 열어 둔 팝업이
	// 응답을 직접 읽으므로 기존 200 JSON 계약을 그대로 둔다).
	private final String loginRedirectUri;

	public OAuthCallbackController(
		OAuthCallbackService callbackService,
		OAuthStateCookieFactory stateCookieFactory,
		@Value("${oauth.login-redirect-uri}")
		String loginRedirectUri) {
		this.callbackService = callbackService;
		this.stateCookieFactory = stateCookieFactory;
		this.loginRedirectUri = loginRedirectUri;
	}

	@GetMapping("/{provider}/callback")
	public ResponseEntity<Object> callback(
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
			URI location = UriComponentsBuilder.fromUriString(loginRedirectUri)
				.queryParam("code", exchangeCode)
				.build()
				.toUri();
			return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
		}
		return ResponseEntity.ok(result);
	}

	// 위 리다이렉트가 실어 보낸 1회용 교환 코드를 실제 토큰으로 바꾼다. 코드는 발급 후 60초 안에 한 번만 쓸 수
	// 있다 — 두 번째 호출이나 만료된 코드는 VALIDATION_ERROR다(OAuthCallbackService.consumeLoginExchangeCode).
	@PostMapping("/login-exchange")
	public ResponseEntity<TokenResponse> exchange(@Valid @RequestBody
	LoginExchangeRequest request) {
		return ResponseEntity.ok(callbackService.consumeLoginExchangeCode(request.code()));
	}
}
