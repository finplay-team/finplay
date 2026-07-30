// 로컬 개발용 KIS 실수집 트리거 결과를 담는 응답 DTO
package com.finplay.api.market.dto.response;

import java.time.LocalDate;

public record StockReplayImportTriggerResponse(
	LocalDate serviceDate,
	LocalDate tradingDate,
	// 수집 후 그 거래일에 저장된 실데이터(KIS) 분봉 수 — 0이면 수집이 실패했다는 뜻이다.
	long collectedKisCandleCount,
	// 재생세션 준비상태 (READY면 시세·차트·주문이 실데이터로 동작한다)
	String preparationStatus,
	// FAILED인 경우의 사유. READY면 null이다.
	String failureReason,
	// 트리거 직후 계산된 시장상태
	String marketStatus) {
}
