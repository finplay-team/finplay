package com.finplay.api.domain.watchlist.dto.response;

import com.finplay.api.domain.watchlist.entity.WatchlistItem;
import java.time.LocalDateTime;

public record WatchlistItemResponse(
	Long watchlistItemId,
	Long instrumentId,
	String market,
	String symbol,
	String name,
	LocalDateTime createdAt) {

	public static WatchlistItemResponse from(WatchlistItem watchlistItem) {
		return new WatchlistItemResponse(
			watchlistItem.getId(),
			watchlistItem.getInstrument().getId(),
			watchlistItem.getInstrument().getMarket().name(),
			watchlistItem.getInstrument().getSymbol(),
			watchlistItem.getInstrument().getName(),
			watchlistItem.getCreatedAt());
	}
}
