// 인증 사용자의 코인 가상 가격 세션 생성·조회 요청을 처리하는 컨트롤러
package com.finplay.api.education.priceruntime.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.education.priceruntime.dto.request.PracticePriceSessionCreateRequest;
import com.finplay.api.education.priceruntime.dto.response.PracticePriceSessionResponse;
import com.finplay.api.education.priceruntime.service.PracticePriceSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/education/practice/price-sessions")
@RequiredArgsConstructor
@Validated
public class PracticePriceSessionController {

	private final PracticePriceSessionService practicePriceSessionService;

	@PostMapping
	public ResponseEntity<PracticePriceSessionResponse> createSession(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestBody @Valid
		PracticePriceSessionCreateRequest request) {
		PracticePriceSessionResponse response = practicePriceSessionService
			.createSession(principal.userId(), request.instrumentId());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/{sessionId}")
	public ResponseEntity<PracticePriceSessionResponse> getSession(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable @Positive(message = "세션 ID는 양수여야 합니다.")
		Long sessionId) {
		return ResponseEntity.ok(practicePriceSessionService.getSession(principal.userId(), sessionId));
	}
}
