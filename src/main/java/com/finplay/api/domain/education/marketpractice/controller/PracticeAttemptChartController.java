// 인증 사용자의 현재 튜토리얼 attempt 차트 조회와 명시적 가격 tick을 처리하는 컨트롤러
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
	// tick만 재시도 경계를 거친다 (이슈 #491) — 3초마다 폴링되는 쓰기 경로이고 실패하면 화면이 멎는다.
	// getChart는 부수효과 없는 순수 조회라 잠금을 잡지 않으므로 거치지 않는다.
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
