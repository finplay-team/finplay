// 지정가 체결 전에 attempt 잠금·검증에 사용할 주문 귀속 스칼라 조회값
package com.finplay.api.domain.order.service;

public record PracticeOrderFillAttributionDto(
	Long attemptId,
	Long runNumber,
	Long userId,
	Long instrumentId) {
}
