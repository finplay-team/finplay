// 인증 사용자의 2단계 → 3단계 대본 전환 요청을 처리하는 컨트롤러
package com.finplay.api.domain.education.marketpractice.controller;

import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.service.PracticeAttemptScriptAdvanceService;
import com.finplay.api.domain.market.entity.Market;
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
public class PracticeAttemptScriptAdvanceController {

	private final PracticeAttemptScriptAdvanceService practiceAttemptScriptAdvanceService;

	@PostMapping("/{market}/advance-script")
	public ResponseEntity<PracticeAttemptResponse> advanceScript(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Market market) {
		return ResponseEntity.ok(practiceAttemptScriptAdvanceService.advanceScript(principal.userId(), market));
	}
}
