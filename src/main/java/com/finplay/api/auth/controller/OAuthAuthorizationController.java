// OAuth 인가 시작 요청을 로그인은 302 리다이렉트로, 재인증은 인가 URI JSON으로 응답한다.
package com.finplay.api.auth.controller;

import com.finplay.api.auth.dto.response.OAuthReauthorizeResponse;
import com.finplay.api.auth.oauth.OAuthAuthorizationResult;
import com.finplay.api.auth.oauth.OAuthStateCookieFactory;
import com.finplay.api.auth.service.OAuthAuthorizationService;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
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
		// SecurityConfig가 이미 login purpose만 공개로 통과시키지만 계약을 컨트롤러에서도 확정한다 (심층방어).
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
