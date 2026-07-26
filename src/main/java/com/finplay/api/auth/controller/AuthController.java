// 이메일 회원가입·로그인·토큰 재발급 요청을 인증 서비스로 전달하고 JWT 응답을 반환하는 컨트롤러
package com.finplay.api.auth.controller;

import com.finplay.api.auth.dto.request.LoginRequest;
import com.finplay.api.auth.dto.request.RefreshRequest;
import com.finplay.api.auth.dto.request.SignupRequest;
import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.service.AuthService;
import com.finplay.api.auth.token.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	@PostMapping("/signup")
	public ResponseEntity<TokenResponse> signup(@Valid @RequestBody
	SignupRequest request) {
		TokenResponse response = authService.signup(
			request.email(),
			request.nickname(),
			request.password(),
			request.signupVerificationToken());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PostMapping("/login")
	public ResponseEntity<TokenResponse> login(@Valid @RequestBody
	LoginRequest request) {
		// 로그인은 리소스 생성이 아니므로 201이 아니라 200이다.
		TokenResponse response = authService.login(request.email(), request.password());
		return ResponseEntity.ok(response);
	}

	@PostMapping("/refresh")
	public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody
	RefreshRequest request) {
		TokenResponse response = authService.refresh(request.refreshToken());
		return ResponseEntity.ok(response);
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		RefreshRequest request) {
		authService.logout(principal.userId(), request.refreshToken());
		return ResponseEntity.noContent().build();
	}
}
