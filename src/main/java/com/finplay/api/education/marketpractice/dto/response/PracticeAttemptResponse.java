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
	LocalDateTime completedAt) {

	public static PracticeAttemptResponse from(PracticeAttempt attempt, PracticeRiskSnapshot snapshot) {
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
			attempt.getCompletedAt());
	}
}
