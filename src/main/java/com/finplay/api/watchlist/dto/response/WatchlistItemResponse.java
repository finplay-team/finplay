// 관심목록에 등록된 종목 한 건의 정보를 반환하는 응답 DTO
package com.finplay.api.watchlist.dto.response;

import com.finplay.api.watchlist.domain.WatchlistItem;
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
