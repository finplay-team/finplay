// OCO 손절·익절 예약 생성·취소를 담당하는 컨트롤러 (021-general-risk-management-oco, 이번 이슈는 일반 경로만)
package com.finplay.api.order.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.order.dto.request.ExitPlanCreateRequest;
import com.finplay.api.order.dto.response.ExitPlanResponse;
import com.finplay.api.order.service.ExitPlanCancelService;
import com.finplay.api.order.service.ExitPlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exit-plans")
@RequiredArgsConstructor
@Validated
public class ExitPlanController {

	private final ExitPlanService exitPlanService;
	private final ExitPlanCancelService exitPlanCancelService;

	// intentionId를 지정하는 교육 경로는 아직 지원하지 않는다(#348 범위는 일반 경로만, service가 400으로 거부한다).
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

	@DeleteMapping("/{exitPlanId}")
	public ResponseEntity<Void> cancelExitPlan(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Long exitPlanId) {
		exitPlanCancelService.cancel(principal.userId(), exitPlanId);
		return ResponseEntity.noContent().build();
	}
}
