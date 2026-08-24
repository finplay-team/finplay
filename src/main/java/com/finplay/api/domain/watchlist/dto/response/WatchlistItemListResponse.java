// 인증 사용자의 관심목록 항목들을 감싸 반환하는 응답 DTO
package com.finplay.api.domain.watchlist.dto.response;

import com.finplay.api.domain.watchlist.entity.WatchlistItem;
import java.util.List;

public record WatchlistItemListResponse(List<WatchlistItemResponse> content) {

	public WatchlistItemListResponse {
		content = List.copyOf(content);
	}

	public static WatchlistItemListResponse from(List<WatchlistItem> watchlistItems) {
		return new WatchlistItemListResponse(
			watchlistItems.stream().map(WatchlistItemResponse::from).toList());
	}
}
