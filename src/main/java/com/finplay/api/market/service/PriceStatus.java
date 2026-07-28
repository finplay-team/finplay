// 종목별 가격 유효성 상태 (시장 전체 개장 상태 StockMarketStatus와는 별개 개념)
package com.finplay.api.market.service;

public enum PriceStatus {
	AVAILABLE,
	UNAVAILABLE
}
