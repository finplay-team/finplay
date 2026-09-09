package com.finplay.api.domain.auth.controller;

import com.finplay.api.domain.auth.dto.request.EmailChangeConfirmRequest;
import com.finplay.api.domain.auth.dto.request.EmailChangeRequest;
import com.finplay.api.domain.auth.dto.response.MemberResponse;
import com.finplay.api.domain.auth.service.AuthService;
import com.finplay.api.domain.auth.service.EmailChangeService;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
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
@RequestMapping("/api/auth/email-changes")
@RequiredArgsConstructor
public class EmailChangeController {

	private final EmailChangeService emailChangeService;
	private final AuthService authService;

	@PostMapping
	public ResponseEntity<Void> requestEmailChange(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		EmailChangeRequest request) {
		emailChangeService.requestEmailChange(
			principal.userId(), request.newEmail(), request.currentPassword(), request.reauthToken());
		return ResponseEntity.status(HttpStatus.ACCEPTED).build();
	}

	@PostMapping("/confirm")
	public ResponseEntity<MemberResponse> confirmEmailChange(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		EmailChangeConfirmRequest request) {
		MemberResponse response = authService.confirmEmailChange(
			principal.userId(), request.newEmail(), request.code());
		return ResponseEntity.ok(response);
	}
}
