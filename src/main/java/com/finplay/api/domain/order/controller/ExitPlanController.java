package com.finplay.api.domain.order.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.order.dto.request.ExitPlanCreateRequest;
import com.finplay.api.domain.order.dto.response.ExitPlanListResponse;
import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.service.ExitPlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exit-plans")
@RequiredArgsConstructor
@Validated
public class ExitPlanController {

	private final ExitPlanService exitPlanService;

	@PostMapping
	public ResponseEntity<ExitPlanResponse> createExitPlan(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 36)
		String idempotencyKey,
		@Valid @RequestBody
		ExitPlanCreateRequest request) {
		ExitPlanResponse response = exitPlanService.create(principal.userId(), idempotencyKey, request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping
	public ResponseEntity<ExitPlanListResponse> getMyExitPlans(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@RequestParam(required = false)
		ExitPlanStatus status) {
		return ResponseEntity.ok(exitPlanService.list(principal.userId(), status));
	}

	@DeleteMapping("/{exitPlanId}")
	public ResponseEntity<Void> cancelExitPlan(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long exitPlanId) {
		exitPlanService.cancel(principal.userId(), exitPlanId);
		return ResponseEntity.noContent().build();
	}
}
