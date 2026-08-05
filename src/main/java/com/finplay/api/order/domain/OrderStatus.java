// 주문의 처리 상태를 나타내는 열거형 (즉시 전량 체결·지정가 체결 대기)
package com.finplay.api.order.domain;

public enum OrderStatus {
	FILLED,
	PENDING
}
