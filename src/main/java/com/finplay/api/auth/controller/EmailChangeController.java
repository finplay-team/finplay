// 인증 사용자의 새 이메일 변경 인증번호 발송 요청을 받는 컨트롤러
package com.finplay.api.auth.controller;

import com.finplay.api.auth.dto.request.EmailChangeRequest;
import com.finplay.api.auth.service.EmailChangeService;
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
@RequestMapping("/api/auth/email-changes")
@RequiredArgsConstructor
public class EmailChangeController {

	private final EmailChangeService emailChangeService;

	// 재인증 증명(현재 비밀번호 또는 reauthToken)을 검증한 뒤 새 이메일로 인증번호를 발송한다. 성공 시 202, 본문 없음.
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
}
