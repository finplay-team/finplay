// 조회 시점에 계산되는 주식 시장 전체의 개장 상태 (DB에 저장하지 않는다 — spec.md 비즈니스 규칙)
package com.finplay.api.domain.market.service;

public enum StockMarketStatus {
	OPEN,
	CLOSED
}
