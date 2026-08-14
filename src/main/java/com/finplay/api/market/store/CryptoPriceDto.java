// Redis에 저장된 코인 심볼의 최신 가격·체결 시각(receivedAt)·관측 시각(observedAt)을 PriceStore가 호출자에게 전달한다.
package com.finplay.api.market.store;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CryptoPriceDto(String symbol, BigDecimal price, LocalDateTime receivedAt, LocalDateTime observedAt) {

	// observedAt을 넘기지 않는 기존 호출부(기존 테스트 등) 호환용 생성자 — observedAt이 없으면 receivedAt으로
	// 폴백한다. PriceStore.getLatestPrice가 Redis에 observedAt 필드가 없는 기존 해시를 다루는 폴백 정책과 같다
	// (docs/specs/034-crypto-price-rest-backup/plan.md).
	public CryptoPriceDto(String symbol, BigDecimal price, LocalDateTime receivedAt) {
		this(symbol, price, receivedAt, receivedAt);
	}
}
