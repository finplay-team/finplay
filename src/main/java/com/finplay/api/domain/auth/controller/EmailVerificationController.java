package com.finplay.api.domain.auth.controller;

import com.finplay.api.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.finplay.api.domain.auth.dto.request.EmailVerificationRequest;
import com.finplay.api.domain.auth.dto.response.SignupTokenResponse;
import com.finplay.api.domain.auth.service.EmailVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/email-verifications")
@RequiredArgsConstructor
public class EmailVerificationController {

	private final EmailVerificationService emailVerificationService;

	@PostMapping
	public ResponseEntity<Void> sendVerificationCode(
		@Valid @RequestBody
		EmailVerificationRequest request) {
		emailVerificationService.sendVerificationCode(request.email());
		return ResponseEntity.status(HttpStatus.ACCEPTED).build();
	}

	@PostMapping("/confirm")
	public ResponseEntity<SignupTokenResponse> confirmVerificationCode(
		@Valid @RequestBody
		EmailVerificationConfirmRequest request) {
		return ResponseEntity.ok(
			emailVerificationService.confirmVerificationCode(request.email(), request.code()));
	}
}
