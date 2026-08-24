// 투자일기 목록과 커서 페이지 메타데이터를 함께 노출하는 응답 DTO
package com.finplay.api.domain.journal.dto.response;

import java.util.List;

public record JournalListResponse(List<JournalListItemResponse> content, String nextCursor, boolean hasNext) {

	public JournalListResponse {
		content = List.copyOf(content);
	}

	public static JournalListResponse of(List<JournalListItemResponse> content, String nextCursor, boolean hasNext) {
		return new JournalListResponse(content, nextCursor, hasNext);
	}
}
