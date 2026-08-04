// 등록된 즐겨찾기의 종목 정보와 등록 시각을 반환하는 응답 DTO
package com.finplay.api.favorite.dto.response;

import com.finplay.api.favorite.domain.Favorite;
import java.time.LocalDateTime;

public record FavoriteResponse(
	Long favoriteId,
	Long instrumentId,
	String market,
	String symbol,
	String name,
	LocalDateTime createdAt) {

	public static FavoriteResponse from(Favorite favorite) {
		return new FavoriteResponse(
			favorite.favoriteId(),
			favorite.instrumentId(),
			favorite.market(),
			favorite.symbol(),
			favorite.name(),
			favorite.createdAt());
	}
}
