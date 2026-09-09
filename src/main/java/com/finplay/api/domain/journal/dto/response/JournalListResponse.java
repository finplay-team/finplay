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
