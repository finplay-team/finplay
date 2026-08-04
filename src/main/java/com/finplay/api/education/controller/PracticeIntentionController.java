// 인증 사용자의 투자 실습 사전 의도 생성 요청을 처리하는 컨트롤러
package com.finplay.api.education.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.education.dto.response.PracticeIntentionResponse;
import com.finplay.api.education.service.PracticeIntentionService;
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
@RequestMapping("/api/education/practice/intentions")
@RequiredArgsConstructor
public class PracticeIntentionController {

	private final PracticeIntentionService practiceIntentionService;

	@PostMapping
	public ResponseEntity<PracticeIntentionResponse> createIntention(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@Valid @RequestBody
		PracticeIntentionCreateRequest request) {
		PracticeIntentionResponse response = practiceIntentionService.createIntention(principal.userId(), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
