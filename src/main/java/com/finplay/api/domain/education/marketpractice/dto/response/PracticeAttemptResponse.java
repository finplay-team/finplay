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
			presetNameOf(rates),
			exitPresetLocked,
			ExitPresetResponse.all(),
			rates.stopLossRate(),
			rates.takeProfitRate(),
			ExitRateBoundsResponse.current());
	}

	private static String presetNameOf(ExitRates rates) {
		ExitPreset matching = rates.matchingPreset();
		return matching == null ? null : matching.name();
	}
}
