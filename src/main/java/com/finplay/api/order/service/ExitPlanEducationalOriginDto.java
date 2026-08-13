// 교육 경로(016 chain 검증)가 확정한 intention·매수 체결 evidence snapshot — 일반 경로에는 존재하지 않는다
package com.finplay.api.order.service;

import com.finplay.api.order.domain.Trade;

/**
 * 이 record가 non-null이면 교육 경로, null이면 일반 경로다. 엔진은 이 값을 해석하지 않고 그대로 snapshot하며,
 * chain 검증(favorite→intention→buyTrade→holding)은 호출부인 education 경로가 이미 끝낸 상태로 넘긴다
 * (021 plan.md "교육 경로 — 변경 없음").
 */
public record ExitPlanEducationalOriginDto(Long intentionId, String intentionInstanceKey, Trade buyTrade) {

	public ExitPlanEducationalOriginDto {
		if (intentionId == null) {
			throw new IllegalArgumentException("교육 경로는 intentionId가 필수입니다.");
		}
		if (intentionInstanceKey == null || intentionInstanceKey.isBlank()) {
			throw new IllegalArgumentException("교육 경로는 intentionInstanceKey가 필수입니다.");
		}
		if (buyTrade == null) {
			throw new IllegalArgumentException("교육 경로는 buyTrade가 필수입니다.");
		}
	}
}
