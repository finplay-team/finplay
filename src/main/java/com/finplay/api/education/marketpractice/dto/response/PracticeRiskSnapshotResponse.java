// 튜토리얼 현재 실행 세대의 자동 손절·익절 위험 스냅샷을 반환하는 응답 DTO
package com.finplay.api.education.marketpractice.dto.response;

import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PracticeRiskSnapshotResponse(
	BigDecimal entryPrice,
	BigDecimal stopLossPrice,
	BigDecimal takeProfitPrice,
	Long buyTradeId,
	LocalDateTime createdAt) {

	public static PracticeRiskSnapshotResponse from(PracticeRiskSnapshot snapshot) {
		return new PracticeRiskSnapshotResponse(
			snapshot.getEntryPrice(),
			snapshot.getStopLossPrice(),
			snapshot.getTakeProfitPrice(),
			snapshot.getBuyTrade().getId(),
			snapshot.getCreatedAt());
	}
}
