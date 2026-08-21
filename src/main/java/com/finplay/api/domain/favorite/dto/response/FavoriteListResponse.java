// 인증 사용자의 즐겨찾기 목록을 감싸 반환하는 응답 DTO
package com.finplay.api.domain.favorite.dto.response;

import com.finplay.api.domain.favorite.model.Favorite;
import java.util.List;

public record FavoriteListResponse(List<FavoriteResponse> content) {

	public FavoriteListResponse {
		content = List.copyOf(content);
	}

	public static FavoriteListResponse from(List<Favorite> favorites) {
		return new FavoriteListResponse(favorites.stream().map(FavoriteResponse::from).toList());
	}
}
