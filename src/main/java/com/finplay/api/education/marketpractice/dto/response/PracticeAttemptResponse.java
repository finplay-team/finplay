// 튜토리얼 attempt의 현재 실행 세대·선택 상태·위험 근거를 반환하는 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptMode;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import java.time.LocalDate;
import java.time.LocalDateTime;

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
	long tutorialRealizedPnl) {

	// 진입·재시작이 아닌 호출부(종목 선택 등)는 그 시점 튜토리얼 계좌를 새로 조회하지 않으므로 0으로 채운다
	// (TUTORIAL-CASH-ISOL-011 범위는 진입·재시작 응답 한정, plan.md "API 설계" 참고).
	public static PracticeAttemptResponse from(PracticeAttempt attempt, PracticeRiskSnapshot snapshot) {
		return from(attempt, snapshot, 0L, 0L, 0L);
	}

	public static PracticeAttemptResponse from(
		PracticeAttempt attempt,
		PracticeRiskSnapshot snapshot,
		long tutorialCashBalance,
		long tutorialAvailableCash,
		long tutorialRealizedPnl) {
		PracticeAttemptMode mode = attempt.getStatus() == PracticeAttemptStatus.COMPLETED
			? PracticeAttemptMode.REPLAY
			: PracticeAttemptMode.ACTIVE;
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
			tutorialRealizedPnl);
	}
}
