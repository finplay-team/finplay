// 현재 attempt 실행 세대의 위험 snapshot·holding·매도 원장과 이번 실행 매매 결과를 묶는 영속 evidence DTO
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.order.domain.Trade;
import java.math.BigDecimal;

/**
 * 뒤 네 필드는 이슈 #421에서 더했다 — 세 수량 필드와 마찬가지로
 * {@code TradeService.summarizePracticeRun}이 준 {@code PracticeRunTradeSummaryDto}를 그대로 풀어 담은
 * 값이며 이 클래스가 다시 계산하지 않는다. 의미·null 규칙은 그 DTO의 문서를 따른다.
 */
public record ResolvedPracticeAttemptEvidenceDto(
	PracticeRiskSnapshot riskSnapshot,
	Long holdingId,
	BigDecimal buyQuantity,
	BigDecimal sellQuantity,
	BigDecimal remainingQuantity,
	Trade sellTrade,
	BigDecimal averageBuyPrice,
	BigDecimal averageSellPrice,
	Long realizedPnl,
	Long soldBuyBasis) {
}
