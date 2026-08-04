// 주문 내역 목록과 커서 페이지 메타데이터를 함께 노출하는 응답 DTO
package com.finplay.api.order.dto.response;

import java.util.List;

public record OrderListResponse(List<OrderListItemResponse> content, String nextCursor, boolean hasNext) {

	public OrderListResponse {
		content = List.copyOf(content);
	}

	public static OrderListResponse of(List<OrderListItemResponse> content, String nextCursor, boolean hasNext) {
		return new OrderListResponse(content, nextCursor, hasNext);
	}
}
