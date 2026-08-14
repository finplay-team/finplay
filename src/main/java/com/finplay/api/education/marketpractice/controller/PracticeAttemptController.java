// 인증 사용자의 튜토리얼 attempt 진입 조회와 샘플 종목 선택 요청을 처리하는 컨트롤러
package com.finplay.api.education.marketpractice.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.education.marketpractice.dto.request.PracticeAttemptInstrumentUpdateRequest;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.service.PracticeAttemptService;
import com.finplay.api.market.domain.Market;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/education/practice/attempts")
@RequiredArgsConstructor
public class PracticeAttemptController {

	private final PracticeAttemptService practiceAttemptService;

	@PutMapping("/{market}")
	public ResponseEntity<PracticeAttemptResponse> ensureAttempt(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Market market) {
		return ResponseEntity.ok(practiceAttemptService.ensureAttempt(principal.userId(), market));
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
