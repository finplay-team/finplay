package com.finplay.api.domain.auth.controller;

import com.finplay.api.domain.auth.dto.response.OAuthReauthorizeResponse;
import com.finplay.api.domain.auth.oauth.OAuthAuthorizationResult;
import com.finplay.api.domain.auth.oauth.state.OAuthStateCookieFactory;
import com.finplay.api.domain.auth.service.OAuthAuthorizationService;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/oauth")
@RequiredArgsConstructor
public class OAuthAuthorizationController {

	private static final String LOGIN_PURPOSE = "login";

	private final OAuthAuthorizationService authorizationService;
	private final OAuthStateCookieFactory stateCookieFactory;

	@GetMapping("/{provider}/authorize")
	public ResponseEntity<Void> authorize(
		@PathVariable
		String provider,
		@RequestParam(required = false)
		String purpose) {
		if (!isLoginPurpose(purpose)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}

		OAuthAuthorizationResult result = authorizationService.authorize(provider);
		ResponseCookie stateCookie = stateCookieFactory.create(result.provider(), result.state());

		return ResponseEntity.status(HttpStatus.FOUND)
			.location(result.authorizationUri())
			.header(HttpHeaders.SET_COOKIE, stateCookie.toString())
			.build();
	}

	@GetMapping(value = "/{provider}/authorize", params = "purpose=reauth")
	public ResponseEntity<OAuthReauthorizeResponse> authorizeReauth(
		@PathVariable
		String provider,
		@AuthenticationPrincipal
		AuthenticatedUser principal) {
		OAuthAuthorizationResult result = authorizationService.authorizeForReauth(provider, principal.userId());
		ResponseCookie stateCookie = stateCookieFactory.create(result.provider(), result.state());

		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, stateCookie.toString())
			.body(new OAuthReauthorizeResponse(result.authorizationUri().toString()));
	}

	private static boolean isLoginPurpose(String purpose) {
		return purpose == null || purpose.isBlank() || LOGIN_PURPOSE.equalsIgnoreCase(purpose);
	}
}
