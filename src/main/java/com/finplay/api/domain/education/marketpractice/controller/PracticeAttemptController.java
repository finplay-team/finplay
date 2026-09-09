package com.finplay.api.domain.education.marketpractice.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeAttemptExitPresetUpdateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeAttemptExitRatesUpdateRequest;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeAttemptInstrumentUpdateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.service.PracticeAttemptDeadlockRetryService;
import com.finplay.api.domain.education.marketpractice.service.PracticeAttemptService;
import com.finplay.api.domain.education.marketpractice.service.PracticeExitPlanReservationService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/education/practice/attempts")
@RequiredArgsConstructor
public class PracticeAttemptController {

	private final PracticeAttemptService practiceAttemptService;
	private final PracticeAttemptDeadlockRetryService practiceAttemptDeadlockRetryService;
	private final PracticeExitPlanReservationService practiceExitPlanReservationService;

	@PutMapping("/{market}")
	public ResponseEntity<PracticeAttemptResponse> ensureAttempt(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Market market) {
		return ResponseEntity.ok(practiceAttemptDeadlockRetryService.ensureAttempt(principal.userId(), market));
	}

	@PutMapping("/{market}/exit-preset")
	public ResponseEntity<PracticeAttemptResponse> selectExitPreset(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Market market,
		@RequestBody @Valid
		PracticeAttemptExitPresetUpdateRequest request) {
		return ResponseEntity.ok(
			practiceAttemptService.selectExitPreset(principal.userId(), market, request.preset()));
	}

	@PutMapping("/{market}/exit-rates")
	public ResponseEntity<PracticeAttemptResponse> selectExitRates(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Market market,
		@RequestBody @Valid
		PracticeAttemptExitRatesUpdateRequest request) {
		return ResponseEntity.ok(
			practiceAttemptService.selectExitRates(principal.userId(), market, request.toExitRates()));
	}

	@PostMapping("/{market}/exit-plan")
	public ResponseEntity<ExitPlanResponse> createExitPlan(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Market market,
		@RequestBody @Valid
		PracticeAttemptExitRatesUpdateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(
			practiceExitPlanReservationService.create(principal.userId(), market, request.toExitRates()));
	}

	@PutMapping("/{market}/instrument")
	public ResponseEntity<PracticeAttemptResponse> selectInstrument(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Market market,
		@RequestBody @Valid
		PracticeAttemptInstrumentUpdateRequest request) {
		return ResponseEntity.ok(
			practiceAttemptService.selectInstrument(principal.userId(), market, request.instrumentId()));
	}
}
