// 비밀번호 재설정 인증번호 발송 요청을 받는 컨트롤러 (비인증 공개 경로)
package com.finplay.api.auth.controller;

import com.finplay.api.auth.dto.request.PasswordResetRequest;
import com.finplay.api.auth.service.PasswordResetService;
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

	// 가입 이메일로 재설정 인증번호를 발송한다. 발송 제한·대상 회원 판정을 통과하면 202로 응답하며 본문은 없다.
	@PostMapping
	public ResponseEntity<Void> sendResetCode(
		@Valid @RequestBody
		PasswordResetRequest request) {
		passwordResetService.sendResetCode(request.email());
		return ResponseEntity.status(HttpStatus.ACCEPTED).build();
	}
}
