// 튜토리얼 실행 세대의 OCO 예약 원장을 한 번 훑어 만든 요약 (052 EXITFREE-020·021)
package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.dto.response.ExitPlanResponse;

/**
 * 052가 예약 생성을 사용자 손으로 옮기면서 화면이 알아야 하는 것이 늘었다 — "지금 예약이 걸려 있는가"와
 * "이 실행에서 손절·익절을 겪었는가". 둘 다 {@code exit_plans} 한 테이블에서 나오므로 <b>조회를 두 번 하지
 * 않고 한 번 훑어 함께 만든다.</b>
 *
 * <p>write-once 판정(그 <b>진입에</b> 이미 만들었는가)은 여기 담지 않는다 — 진입 순번을 아는 호출부가
 * {@code PracticeExitPlanQueryService.existsEntryReservation}으로 따로 묻는다.
 *
 * <p><b>새 컬럼도 새 저장소도 만들지 않는다</b>(052 EXITFREE-021). 겪음 판정을 원장에서 파생하면 재시작
 * 초기화가 저절로 성립한다 — run이 올라가면 이전 세대 행이 조회 범위에서 통째로 빠지므로 "초기화를
 * 빠뜨렸다"는 사고(041 잔여 위험 4)가 아예 성립하지 않는다.
 *
 * @param stopLossFilled   {@code FILLED_STOP_LOSS}로 체결된 예약이 있었는가
 * @param takeProfitFilled {@code FILLED_TAKE_PROFIT}으로 체결된 예약이 있었는가
 * @param pendingPlan      지금 대기 중인 예약. 없으면 {@code null}이다. 실행 세대당 PENDING은 최대 1건이다
 *                         (엔진의 holding당 PENDING 1건 불변식이 보장한다)
 */
public record PracticeRunExitPlanSummaryDto(
	boolean stopLossFilled, boolean takeProfitFilled, ExitPlanResponse pendingPlan) {

	public static PracticeRunExitPlanSummaryDto empty() {
		return new PracticeRunExitPlanSummaryDto(false, false, null);
	}
}
