// 종목별 가격 유효성 상태 (시장 전체 개장 상태 StockMarketStatus와는 별개 개념)
package com.finplay.api.market.service;

public enum PriceStatus {
	AVAILABLE,
	STALE, // 연결 유지 + 마지막 수신 틱이 10초 초과(표시 전용 완화, PRICE-STALE-001). price·sourceTime은 non-null.
	UNAVAILABLE
}
