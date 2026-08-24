// 튜토리얼 현재 실행 세대의 자동 손절·익절 위험 스냅샷을 반환하는 응답 DTO
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
		// 052 — 정본은 이제 비율 두 컬럼이다. 042 이전 행(둘 다 null)은 exit_preset -> 기본값 순으로
		// 해석해 내려보낸다(EXITPRESET-002) — 화면이 "기준을 모르는 진입"을 그리지 않게 한다.
		ExitRates rates = snapshot.appliedExitRates();
		ExitPreset matching = rates.matchingPreset();
		return new PracticeRiskSnapshotResponse(
			snapshot.getEntryPrice(),
			snapshot.getStopLossPrice(),
			snapshot.getTakeProfitPrice(),
			snapshot.getBuyTrade().getId(),
			snapshot.getCreatedAt(),
			// **052부터 nullable이다** — 자유 조합 진입은 맞는 프리셋이 없다. 비율 두 필드는 그래도
			// 항상 채워지므로 화면은 그쪽으로 그린다.
			matching == null ? null : matching.name(),
			rates.stopLossRate(),
			rates.takeProfitRate(),
			snapshot.getEntrySequence());
	}
}
