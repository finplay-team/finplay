// 현재 attempt 실행 세대의 위험 snapshot·holding·매도 원장과 이번 실행 매매 결과를 묶는 영속 evidence DTO
package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.entity.PracticeSellCause;
import com.finplay.api.domain.order.entity.Trade;
import java.math.BigDecimal;

/**
 * {@code riskSnapshot}은 그 실행 세대의 <b>최신 진입</b>이고 {@code observationBaseline}은 <b>첫 진입</b>이다.
 * 재진입(손절 후 재매수)이 도입되면 둘이 갈라진다 — 화면의 "지금 내 손절·익절선"은 최신 진입을 따라가야 하지만,
 * 관찰 evidence를 거르는 기준선은 첫 진입에 고정돼야 한다. 최신을 기준선으로 쓰면 재매수 순간 그 이전 관찰이
 * 필터에서 사라져 3단계가 미완료로 되돌아간다(이슈 #420과 같은 유형). evidence는 실행 세대 단위 개념이고
 * snapshot은 진입 단위 개념이라는 차이가 여기서 처음 드러난다(041 SCENARIO-019a).
 *
 * <p>뒤 네 필드는 이슈 #421에서 더했다 — 세 수량 필드와 마찬가지로
 * {@code TradeService.summarizePracticeRun}이 준 {@code PracticeRunTradeSummaryDto}를 그대로 풀어 담은
 * 값이며 이 클래스가 다시 계산하지 않는다. 의미·null 규칙은 그 DTO의 문서를 따른다.
 */
public record ResolvedPracticeAttemptEvidenceDto(
	PracticeRiskSnapshot riskSnapshot,
	PracticeRiskSnapshot observationBaseline,
	Long holdingId,
	BigDecimal buyQuantity,
	BigDecimal sellQuantity,
	BigDecimal remainingQuantity,
	Trade sellTrade,
	BigDecimal averageBuyPrice,
	BigDecimal averageSellPrice,
	Long realizedPnl,
	Long soldBuyBasis,
	PracticeSellCause sellCause) {
}
