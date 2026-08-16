// 현재 attempt 실행 세대의 위험 snapshot·holding·매도 원장을 묶는 영속 evidence DTO
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.order.domain.Trade;
import java.math.BigDecimal;

public record ResolvedPracticeAttemptEvidenceDto(
	PracticeRiskSnapshot riskSnapshot,
	Long holdingId,
	BigDecimal buyQuantity,
	BigDecimal sellQuantity,
	BigDecimal remainingQuantity,
	Trade sellTrade) {
}
