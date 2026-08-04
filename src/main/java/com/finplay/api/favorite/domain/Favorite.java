// 인증 사용자가 관심 종목으로 등록한 즐겨찾기를 표현하는 인메모리 불변 값 객체(#193: DB 엔티티에서 전환)
package com.finplay.api.favorite.domain;

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
