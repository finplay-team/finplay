package com.finplay.api.domain.order.dto.response;

import java.util.List;

public record ExitPlanListResponse(List<ExitPlanResponse> content) {

	public ExitPlanListResponse {
		content = List.copyOf(content);
	}

	public static ExitPlanListResponse from(List<ExitPlanResponse> content) {
		return new ExitPlanListResponse(content);
	}
}
