// OCO 손절·익절 예약 목록을 감싸 반환하는 응답 DTO (021 plan.md "응답 계약" — 생성·조회·취소가 ExitPlanResponse를 공유)
package com.finplay.api.order.dto.response;

import java.util.List;

public record ExitPlanListResponse(List<ExitPlanResponse> content) {

	public ExitPlanListResponse {
		content = List.copyOf(content);
	}

	public static ExitPlanListResponse from(List<ExitPlanResponse> content) {
		return new ExitPlanListResponse(content);
	}
}
