package com.finplay.api.domain.education.priceruntime.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.education.priceruntime.dto.request.PracticePriceSessionCreateRequest;
import com.finplay.api.domain.education.priceruntime.dto.request.PracticePriceTickAdvanceRequest;
import com.finplay.api.domain.education.priceruntime.dto.response.PracticePriceSessionResponse;
import com.finplay.api.domain.education.priceruntime.service.PracticePriceSessionService;
import com.finplay.api.domain.education.priceruntime.service.PracticePriceTickService;
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
	private final PracticePriceTickService practicePriceTickService;

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

	@PostMapping("/{sessionId}/ticks")
	public ResponseEntity<PracticePriceSessionResponse> advanceTick(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable @Positive(message = "세션 ID는 양수여야 합니다.")
		Long sessionId,
		@RequestBody @Valid
		PracticePriceTickAdvanceRequest request) {
		return ResponseEntity.ok(
			practicePriceTickService.advanceTick(principal.userId(), sessionId, request.expectedTick()));
	}
}
