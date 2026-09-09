package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.entity.Trade;

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
