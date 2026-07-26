// OAuth callback 요청의 state 쿠키를 소비하고 FinPlay 토큰 응답을 반환한다.
package com.finplay.api.auth.controller;

import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.oauth.OAuthStateCookieFactory;
import com.finplay.api.auth.service.OAuthCallbackService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/oauth")
@RequiredArgsConstructor
public class OAuthCallbackController {

	private final OAuthCallbackService callbackService;
	private final OAuthStateCookieFactory stateCookieFactory;

	@GetMapping("/{provider}/callback")
	public ResponseEntity<TokenResponse> callback(
		@PathVariable
		String provider,
		@RequestParam(required = false)
		String code,
		@RequestParam(required = false)
		String state,
		@CookieValue(name = "oauth_state", required = false)
		String cookieState,
		HttpServletResponse response) {
		response.addHeader(HttpHeaders.SET_COOKIE, stateCookieFactory.expire(provider).toString());

		return ResponseEntity.ok(callbackService.callback(provider, code, state, cookieState));
	}
}
