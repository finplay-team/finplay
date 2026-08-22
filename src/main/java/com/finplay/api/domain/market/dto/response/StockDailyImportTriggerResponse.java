// 로컬 개발용 KIS 일봉 아카이브 실수집 트리거 결과를 담는 응답 DTO
package com.finplay.api.domain.market.dto.response;

import java.time.LocalDate;

public record StockDailyImportTriggerResponse(
	LocalDate serviceDate,
	LocalDate targetEndDate,
	// 이번 실행에서 새로 저장된 일봉 수 — 0이면 이미 targetEndDate까지 저장돼 있었거나(정상, 재실행 멱등)
	// 수집이 실패했다는 뜻이다. importStatus로 구분한다.
	long newlyCollectedCount,
	// stock_daily_candles에 KIS_DAILY로 누적 저장된 전체 일봉 수(전 종목 합계)
	long totalArchivedCount,
	// targetEndDate 기준 가장 최근 수집 이력 상태 — SUCCESS/PARTIAL_SUCCESS/FAILED. 이력이 없으면 null.
	String importStatus,
	// PARTIAL_SUCCESS·FAILED인 경우의 실패 사유 요약. 그 외에는 null이다.
	String failureReason) {
}
