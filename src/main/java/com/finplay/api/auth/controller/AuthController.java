// 이메일 회원가입 요청을 검증해 인증 서비스로 전달하고 JWT 응답을 반환하는 컨트롤러
package com.finplay.api.auth.controller;

import com.finplay.api.auth.dto.request.SignupRequest;
import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
	public ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
		TokenResponse response = authService.signup(
			request.email(),
			request.nickname(),
			request.password(),
			request.signupVerificationToken());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
