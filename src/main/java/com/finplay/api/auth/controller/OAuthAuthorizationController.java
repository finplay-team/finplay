// OAuth 인가 시작 요청을 공급자 인가 페이지로 리다이렉트한다.
package com.finplay.api.auth.controller;

import com.finplay.api.auth.oauth.OAuthAuthorizationResult;
import com.finplay.api.auth.oauth.OAuthStateCookieFactory;
import com.finplay.api.auth.service.OAuthAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/oauth")
@RequiredArgsConstructor
public class OAuthAuthorizationController {

	private final OAuthAuthorizationService authorizationService;
	private final OAuthStateCookieFactory stateCookieFactory;

	@GetMapping("/{provider}/authorize")
	public ResponseEntity<Void> authorize(
		@PathVariable
		String provider) {
		OAuthAuthorizationResult result = authorizationService.authorize(provider);
		ResponseCookie stateCookie = stateCookieFactory.create(result.provider(), result.state());

		return ResponseEntity.status(HttpStatus.FOUND)
			.location(result.authorizationUri())
			.header(HttpHeaders.SET_COOKIE, stateCookie.toString())
			.build();
	}
}
