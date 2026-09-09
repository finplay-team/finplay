package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PracticeRiskSnapshotResponse(
	BigDecimal entryPrice,
	BigDecimal stopLossPrice,
	BigDecimal takeProfitPrice,
	Long buyTradeId,
	LocalDateTime createdAt,
	String exitPreset,
	BigDecimal stopLossRate,
	BigDecimal takeProfitRate,
	int entrySequence) {

	public static PracticeRiskSnapshotResponse from(PracticeRiskSnapshot snapshot) {
		ExitRates rates = snapshot.appliedExitRates();
		ExitPreset matching = rates.matchingPreset();
		return new PracticeRiskSnapshotResponse(
			snapshot.getEntryPrice(),
			snapshot.getStopLossPrice(),
			snapshot.getTakeProfitPrice(),
			snapshot.getBuyTrade().getId(),
			snapshot.getCreatedAt(),
			matching == null ? null : matching.name(),
			rates.stopLossRate(),
			rates.takeProfitRate(),
			snapshot.getEntrySequence());
	}
}
