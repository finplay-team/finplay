// 인증 사용자의 현재 튜토리얼 attempt 차트 조회와 명시적 가격 tick을 처리하는 컨트롤러
package com.finplay.api.education.marketpractice.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.education.marketpractice.service.PracticeAttemptChartService;
import com.finplay.api.market.domain.Market;
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
		return ResponseEntity.ok(practiceAttemptChartService.tick(principal.userId(), market));
	}
}
