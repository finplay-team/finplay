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
