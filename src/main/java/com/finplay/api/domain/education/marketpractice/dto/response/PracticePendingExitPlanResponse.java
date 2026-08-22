// 현재 실행 세대에 걸려 있는 튜토리얼 손절·익절 예약 한 건을 화면에 내려보내는 응답 DTO (052 EXITFREE-020)
package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 진행 조회의 {@code pendingExitPlan}이며 <b>예약이 없으면 이 객체 자체가 {@code null}</b>이다 — 안쪽 필드가
 * null인 껍데기를 내려보내지 않는다. 화면은 "이 값이 있으면 예약이 걸려 있다"로 읽으면 된다.
 *
 * <p><b>{@code exitPlanId}를 함께 싣는 이유는 취소 때문이다.</b> 예약 취소는 052가 새 경로를 만들지 않고
 * 경로 공통인 {@code DELETE /api/exit-plans/{id}}를 그대로 쓴다(021). 그 id를 여기서 주지 않으면 화면이
 * 예약 목록 API를 따로 호출해야 한다.
 *
 * <p>비율 두 값은 <b>퍼센트 수이고 손절도 양수</b>다(019 EXIT-PRICE-003 표기 규칙). 가격선은 그 예약이
 * 확정한 값이며, 뒤에 사용자가 비율을 다시 정해도 <b>움직이지 않는다</b> — 확정된 예약은 어떤 경로로도
 * 바뀌지 않는다(052 §비즈니스 규칙).
 */
public record PracticePendingExitPlanResponse(
	Long exitPlanId,
	BigDecimal stopLossRate,
	BigDecimal takeProfitRate,
	BigDecimal stopLossPrice,
	BigDecimal takeProfitPrice,
	BigDecimal entryPrice,
	BigDecimal quantity,
	LocalDateTime reservedAt) {

	public static PracticePendingExitPlanResponse from(ExitPlanResponse plan) {
		return plan == null
			? null
			: new PracticePendingExitPlanResponse(
				plan.id(),
				plan.stopLossRate(),
				plan.takeProfitRate(),
				plan.stopLossPrice(),
				plan.takeProfitPrice(),
				plan.entryPrice(),
				plan.quantity(),
				plan.reservedAt());
	}
}
