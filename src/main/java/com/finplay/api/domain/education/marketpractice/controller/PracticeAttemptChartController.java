package com.finplay.api.domain.education.marketpractice.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.domain.education.marketpractice.service.PracticeAttemptChartService;
import com.finplay.api.domain.education.marketpractice.service.PracticeAttemptDeadlockRetryService;
import com.finplay.api.domain.market.entity.Market;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/education/practice/attempts")
@RequiredArgsConstructor
public class PracticeAttemptChartController {

	private final PracticeAttemptChartService practiceAttemptChartService;
	private final PracticeAttemptDeadlockRetryService practiceAttemptDeadlockRetryService;

	@GetMapping("/{market}/chart")
	public ResponseEntity<PracticeTutorialChartResponse> getChart(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Market market) {
		return ResponseEntity.ok(practiceAttemptChartService.getChart(principal.userId(), market));
	}

	@PostMapping("/{market}/tick")
	public ResponseEntity<PracticeTutorialChartResponse> tick(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Market market) {
		return ResponseEntity.ok(practiceAttemptDeadlockRetryService.tick(principal.userId(), market));
	}
}
