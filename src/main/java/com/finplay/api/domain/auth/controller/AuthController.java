package com.finplay.api.domain.auth.controller;

import com.finplay.api.domain.auth.dto.request.LoginRequest;
import com.finplay.api.domain.auth.dto.request.NicknameUpdateRequest;
import com.finplay.api.domain.auth.dto.request.PasswordChangeRequest;
import com.finplay.api.domain.auth.dto.request.RefreshRequest;
import com.finplay.api.domain.auth.dto.request.SignupRequest;
import com.finplay.api.domain.auth.dto.response.MemberResponse;
import com.finplay.api.domain.auth.dto.response.TokenResponse;
import com.finplay.api.domain.auth.service.AuthService;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

	@GetMapping("/me")
	public ResponseEntity<MemberResponse> me(
		@AuthenticationPrincipal
		AuthenticatedUser principal) {
		return ResponseEntity.ok(authService.getMe(principal.userId()));
	}

	@PatchMapping("/me/nickname")
	public ResponseEntity<MemberResponse> updateNickname(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		NicknameUpdateRequest request) {
		MemberResponse response = authService.changeNickname(
			principal.userId(),
			request.nickname(),
			request.currentPassword(),
			request.reauthToken());
		return ResponseEntity.ok(response);
	}

	@PatchMapping("/me/password")
	public ResponseEntity<TokenResponse> updatePassword(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		PasswordChangeRequest request) {
		TokenResponse response = authService.changePassword(
			principal.userId(), request.currentPassword(), request.newPassword());
		return ResponseEntity.ok(response);
	}
}
