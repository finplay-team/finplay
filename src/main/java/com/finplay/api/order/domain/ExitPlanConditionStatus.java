// OCO 개별 조건의 상태를 나타내는 열거형 (체결 조건은 TRIGGERED, 반대쪽은 CANCELLED_BY_OCO)
package com.finplay.api.order.domain;

public enum ExitPlanConditionStatus {
	PENDING,
	TRIGGERED,
	CANCELLED,
	CANCELLED_BY_OCO
}
