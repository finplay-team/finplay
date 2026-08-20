// 튜토리얼 매도가 손절·익절 예약으로 일어났는지 사용자가 직접 팔았는지 구분하는 열거형 (042 EXITPRESET-008)
package com.finplay.api.education.marketpractice.domain;

import com.finplay.api.order.domain.ExitPlanStatus;

/**
 * {@code sellVerdict}로는 구분할 수 없다 — 자동 예약 체결이면 그 값이 정의상 항상 경계값이 되기 때문이다.
 * 판정 근거는 {@code exit_plans.triggered_order_id} 역참조 하나뿐이며, 예약이 가리키지 않는 매도 주문은
 * 전부 {@code MANUAL}이다(예약 자체가 없는 STOCK 튜토리얼과 기능 도입 전 실행도 여기 들어간다).
 */
public enum PracticeSellCause {
	STOP_LOSS,
	TAKE_PROFIT,
	MANUAL;

	/**
	 * 매도 주문을 발동시킨 예약의 최종 상태를 매도 원인으로 옮긴다. 예약이 가리키지 않는 매도({@code null})는
	 * {@code MANUAL}이다.
	 *
	 * <p>실행 전체의 매도 원인({@code tradeResult.sellCause})과 진입별 배열이 <b>같은 판정</b>을 쓰게
	 * 하려고 여기 둔다 — 두 벌로 두면 한쪽만 고쳐도 같은 매도가 화면 두 곳에서 다른 원인으로 보인다.
	 */
	public static PracticeSellCause from(ExitPlanStatus triggeredExitPlanStatus) {
		if (triggeredExitPlanStatus == ExitPlanStatus.FILLED_STOP_LOSS) {
			return STOP_LOSS;
		}
		if (triggeredExitPlanStatus == ExitPlanStatus.FILLED_TAKE_PROFIT) {
			return TAKE_PROFIT;
		}
		return MANUAL;
	}
}
