// 이메일 인증번호 발송 요청을 받는 컨트롤러
package com.finplay.api.auth.controller;

import com.finplay.api.auth.dto.request.EmailVerificationConfirmRequest;
import com.finplay.api.auth.dto.request.EmailVerificationRequest;
import com.finplay.api.auth.dto.response.SignupTokenResponse;
import com.finplay.api.auth.service.EmailVerificationService;
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

	// 인증번호를 발송한다. 발송 제한 통과 시 202로 응답하며 본문은 없다.
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
