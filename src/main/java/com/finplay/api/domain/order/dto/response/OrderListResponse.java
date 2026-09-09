package com.finplay.api.domain.order.dto.response;

import java.util.List;

public record OrderListResponse(List<OrderListItemResponse> content, String nextCursor, boolean hasNext) {

	public OrderListResponse {
		content = List.copyOf(content);
	}

	public static OrderListResponse of(List<OrderListItemResponse> content, String nextCursor, boolean hasNext) {
		return new OrderListResponse(content, nextCursor, hasNext);
	}
}
