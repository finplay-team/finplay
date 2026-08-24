// 캔들 목록과 커서 페이지 메타데이터를 함께 노출하는 응답 DTO
package com.finplay.api.domain.market.dto.response;

import java.util.List;

public record CandleListResponse(List<CandleResponse> content, String nextCursor, boolean hasNext) {

	public CandleListResponse {
		content = List.copyOf(content);
	}

	public static CandleListResponse of(List<CandleResponse> content, String nextCursor, boolean hasNext) {
		return new CandleListResponse(content, nextCursor, hasNext);
	}
}
