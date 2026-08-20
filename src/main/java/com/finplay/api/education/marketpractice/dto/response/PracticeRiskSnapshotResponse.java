// 튜토리얼 현재 실행 세대의 자동 손절·익절 위험 스냅샷을 반환하는 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

import com.finplay.api.education.marketpractice.domain.ExitPreset;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
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
		// 기능 도입 전에 만들어진 행은 exit_preset이 null이며 기본 프리셋으로 해석해 내려보낸다
		// (042 EXITPRESET-002) — 화면이 "기준을 모르는 진입"을 그리지 않게 한다.
		ExitPreset preset = snapshot.getExitPreset() == null ? ExitPreset.DEFAULT : snapshot.getExitPreset();
		return new PracticeRiskSnapshotResponse(
			snapshot.getEntryPrice(),
			snapshot.getStopLossPrice(),
			snapshot.getTakeProfitPrice(),
			snapshot.getBuyTrade().getId(),
			snapshot.getCreatedAt(),
			preset.name(),
			preset.stopLossRate(),
			preset.takeProfitRate(),
			snapshot.getEntrySequence());
	}
}
