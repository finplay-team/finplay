package com.finplay.api.domain.auth.controller;

import com.finplay.api.domain.auth.dto.request.PasswordResetConfirmRequest;
import com.finplay.api.domain.auth.dto.request.PasswordResetRequest;
import com.finplay.api.domain.auth.service.AuthService;
import com.finplay.api.domain.auth.service.PasswordResetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/password-resets")
@RequiredArgsConstructor
public class PasswordResetController {

	private final PasswordResetService passwordResetService;
	private final AuthService authService;

	@PostMapping
	public ResponseEntity<Void> sendResetCode(
		@Valid @RequestBody
		PasswordResetRequest request) {
		passwordResetService.sendResetCode(request.email());
		return ResponseEntity.status(HttpStatus.ACCEPTED).build();
	}

	@PostMapping("/confirm")
	public ResponseEntity<Void> confirmReset(
		@Valid @RequestBody
		PasswordResetConfirmRequest request) {
		authService.confirmPasswordReset(request.email(), request.code(), request.newPassword());
		return ResponseEntity.noContent().build();
	}
}
