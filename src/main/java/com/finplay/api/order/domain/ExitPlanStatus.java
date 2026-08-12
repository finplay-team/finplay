// OCO 손절·익절 예약의 상태를 나타내는 열거형 (021은 코인 GTC만 다뤄 세션 만료 상태가 없다)
package com.finplay.api.order.domain;

public enum ExitPlanStatus {
	PENDING,
	FILLED_TAKE_PROFIT,
	FILLED_STOP_LOSS,
	CANCELLED
}
