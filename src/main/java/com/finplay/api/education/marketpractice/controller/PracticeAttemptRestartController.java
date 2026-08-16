// 인증 사용자의 튜토리얼 attempt 명시적 재시작 요청을 처리하는 컨트롤러
package com.finplay.api.education.marketpractice.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.service.PracticeAttemptRestartService;
import com.finplay.api.market.domain.Market;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/education/practice/attempts")
@RequiredArgsConstructor
public class PracticeAttemptRestartController {

	private final PracticeAttemptRestartService practiceAttemptRestartService;

	@PostMapping("/{market}/restart")
	public ResponseEntity<PracticeAttemptResponse> restart(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Market market) {
		return ResponseEntity.ok(practiceAttemptRestartService.restart(principal.userId(), market));
	}
}
