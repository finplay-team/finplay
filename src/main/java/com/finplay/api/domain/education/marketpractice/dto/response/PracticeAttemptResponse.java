// 튜토리얼 attempt의 현재 실행 세대·선택 상태·위험 근거를 반환하는 응답 DTO
package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptMode;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PracticeAttemptResponse(
	Long attemptId,
	String market,
	long runNumber,
	String mode,
	String status,
	Long instrumentId,
	LocalDateTime anchorAt,
	LocalDate tutorialDate,
	PracticeRiskSnapshotResponse riskSnapshot,
	LocalDateTime completedAt,
	long tutorialCashBalance,
	long tutorialAvailableCash,
	long tutorialRealizedPnl,
	String selectedExitPreset,
	boolean exitPresetLocked,
	List<ExitPresetResponse> availableExitPresets,
	BigDecimal exitStopLossRate,
	BigDecimal exitTakeProfitRate,
	ExitRateBoundsResponse exitRateBounds) {

	public PracticeAttemptResponse {
		availableExitPresets = availableExitPresets == null ? List.of() : List.copyOf(availableExitPresets);
	}

	// 튜토리얼 계좌를 조회하지 않는 호출부는 세 필드를 0으로 채운다. 남은 곳은 진행 조회
	// (GET /api/education/practice의 attempt 필드)뿐이다 — tick과 함께 폴링되는 경로라 호출마다 계좌를
	// 한 번 더 읽지 않는다. 쓰기 경로 네 곳(진입·재시작·종목 선택·프리셋 선택)은 모두 실값을 싣는다
	// (TUTORIAL-CASH-ISOL-011, 뒤 둘은 이슈 #502에서 더했다).
	//
	// exitPresetLocked만은 기본값을 두지 않고 호출부가 반드시 넘기게 한다(042 EXITPRESET-003). 잠금 여부는
	// 현재 순보유수량을 조회해야 알 수 있고, 잘못 false로 내리면 클라이언트가 바꿀 수 없는 프리셋 선택
	// 컨트롤을 열어 준다 — 계좌 잔고 0처럼 "이 호출부는 모른다"로 넘길 수 있는 값이 아니다.
	public static PracticeAttemptResponse from(
		PracticeAttempt attempt, PracticeRiskSnapshot snapshot, boolean exitPresetLocked) {
		return from(attempt, snapshot, exitPresetLocked, 0L, 0L, 0L);
	}

	public static PracticeAttemptResponse from(
		PracticeAttempt attempt,
		PracticeRiskSnapshot snapshot,
		boolean exitPresetLocked,
		long tutorialCashBalance,
		long tutorialAvailableCash,
		long tutorialRealizedPnl) {
		PracticeAttemptMode mode = attempt.getStatus() == PracticeAttemptStatus.COMPLETED
			? PracticeAttemptMode.REPLAY
			: PracticeAttemptMode.ACTIVE;
		ExitRates rates = attempt.effectiveExitRates();
		return new PracticeAttemptResponse(
			attempt.getId(),
			attempt.getMarket().name(),
			attempt.getRunNumber(),
			mode.name(),
			attempt.getStatus().name(),
			attempt.getInstrument() == null ? null : attempt.getInstrument().getId(),
			attempt.getAnchorAt(),
			attempt.getTutorialDate(),
			snapshot == null ? null : PracticeRiskSnapshotResponse.from(snapshot),
			attempt.getCompletedAt(),
			tutorialCashBalance,
			tutorialAvailableCash,
			tutorialRealizedPnl,
			// 052 — 프리셋 이름은 이제 파생값이다. 적용될 비율이 프리셋 3개 중 하나와 정확히 같으면 그
			// 식별자를, 자유 조합이면 null을 담는다. **미선택 사용자는 기본값(3·5)이 BALANCED와 같아
			// 지금까지와 똑같이 "BALANCED"를 받는다** — 042 EXITPRESET-002가 약속한 것이 그대로 유지된다.
			// 프론트가 프리셋 픽커를 떼면 이 필드와 availableExitPresets를 함께 없앤다.
			presetNameOf(rates),
			exitPresetLocked,
			ExitPresetResponse.all(),
			// 항상 non-null이다 — 미선택도 기본값이 실린다(클라이언트에 null 분기를 만들지 않는다).
			rates.stopLossRate(),
			rates.takeProfitRate(),
			ExitRateBoundsResponse.current());
	}

	private static String presetNameOf(ExitRates rates) {
		ExitPreset matching = rates.matchingPreset();
		return matching == null ? null : matching.name();
	}
}
