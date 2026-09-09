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
