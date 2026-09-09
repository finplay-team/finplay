package com.finplay.api.domain.favorite.model;

import java.time.LocalDateTime;

public record Favorite(
	Long favoriteId,
	Long userId,
	Long instrumentId,
	String market,
	String symbol,
	String name,
	LocalDateTime createdAt) {

	public static Favorite create(
		Long favoriteId,
		Long userId,
		Long instrumentId,
		String market,
		String symbol,
		String name,
		LocalDateTime createdAt) {
		return new Favorite(favoriteId, userId, instrumentId, market, symbol, name, createdAt);
	}
}
