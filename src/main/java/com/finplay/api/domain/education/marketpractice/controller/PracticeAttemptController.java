// 인증 사용자의 튜토리얼 attempt 진입 조회·샘플 종목 선택·손절익절 기준 선택·예약 생성 요청을 처리하는 컨트롤러
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
	// 진입은 재시도 경계를 한 겹 거친다 (이슈 #491) — 종목 선택·프리셋 선택은 사용자가 같은 버튼을 다시
	// 누르면 되므로 거치지 않는다.
	private final PracticeAttemptDeadlockRetryService practiceAttemptDeadlockRetryService;
	// 052 EXITFREE-020 — 사용자 주도 예약 생성. attempt 잠금·엔진 호출을 그 서비스가 한 트랜잭션으로 묶는다.
	private final PracticeExitPlanReservationService practiceExitPlanReservationService;

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

	/**
	 * 052 — 손절 비율·익절 비율을 서로 독립적으로 자유 입력한다. 위 {@code exit-preset}을 대체하지만
	 * <b>지우지 않는다</b> — 프론트가 별도 레포·별도 배포라 같은 배포에서 없애면 깨진다.
	 *
	 * <p>{@code PUT}인 이유도 위와 같다(자연 멱등). 검증(구간·소수 자릿수)은 요청 DTO의 Bean Validation이
	 * 하고 위반은 400 {@code VALIDATION_ERROR}로 나간다.
	 */
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

	/**
	 * 052 EXITFREE-020 — 사용자가 <b>직접</b> 손절·익절 예약을 건다. 042는 매수 체결 순간 서버가 대신
	 * 걸었지만(EXITPRESET-004·012), 그러면 사용자가 걸 것이 남지 않아 이 튜토리얼이 가르치려는 행위 자체가
	 * 사라진다. 실전 화면의 순서(매수 → 보유 → 매도 화면의 예약매도)와 같게 맞춘 것이다.
	 *
	 * <p>요청 본문은 {@code PUT .../exit-rates}와 <b>같은 DTO</b>다 — 구간·소수 자릿수·양수 규칙이 같아야
	 * 하는데 검증을 복제하면 두 화면이 서로 다른 값을 받는 상태가 조용히 생긴다.
	 *
	 * <p><b>{@code POST}이고 멱등이 아니다.</b> 예약은 진입당 한 번만 만들 수 있고(write-once) 두 번째
	 * 요청은 409 {@code EXIT_PLAN_ALREADY_EXISTS}다. 취소는 새 경로를 만들지 않고 경로 공통인
	 * {@code DELETE /api/exit-plans/{id}}를 그대로 쓴다.
	 */
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
