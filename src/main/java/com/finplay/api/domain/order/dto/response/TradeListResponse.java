package com.finplay.api.domain.order.dto.response;

import java.util.List;

public record TradeListResponse(List<TradeListItemResponse> content, String nextCursor, boolean hasNext) {

	public TradeListResponse {
		content = List.copyOf(content);
	}

	public static TradeListResponse of(List<TradeListItemResponse> content, String nextCursor, boolean hasNext) {
		return new TradeListResponse(content, nextCursor, hasNext);
	}
}
