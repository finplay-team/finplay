// 인증 사용자의 튜토리얼 attempt 진입 조회·샘플 종목 선택·손절익절 프리셋 선택 요청을 처리하는 컨트롤러
package com.finplay.api.education.marketpractice.controller;

import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.education.marketpractice.dto.request.PracticeAttemptExitPresetUpdateRequest;
import com.finplay.api.education.marketpractice.dto.request.PracticeAttemptInstrumentUpdateRequest;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.service.PracticeAttemptDeadlockRetryService;
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
	// 진입은 재시도 경계를 한 겹 거친다 (이슈 #491) — 종목 선택·프리셋 선택은 사용자가 같은 버튼을 다시
	// 누르면 되므로 거치지 않는다.
	private final PracticeAttemptDeadlockRetryService practiceAttemptDeadlockRetryService;

	@PutMapping("/{market}")
	public ResponseEntity<PracticeAttemptResponse> ensureAttempt(
		@AuthenticationPrincipal
		AuthenticatedUser principal,
		@PathVariable
		Market market) {
		return ResponseEntity.ok(practiceAttemptDeadlockRetryService.ensureAttempt(principal.userId(), market));
	}

	// PUT인 이유는 자연 멱등이기 때문이다 — 같은 값을 몇 번 보내도 결과가 같고 체결 전이면 몇 번이든 바꿀
	// 수 있다(042 EXITPRESET-003). Idempotency-Key는 요구하지 않는다(015 LMT-005의 PATCH와 같은 판단).
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
