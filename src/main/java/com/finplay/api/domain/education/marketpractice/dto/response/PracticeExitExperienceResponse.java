// 현재 실행 세대에서 손절·익절을 실제로 겪었는지와 다음에 권하는 쪽을 내려보내는 응답 DTO (052 EXITFREE-021·022)
package com.finplay.api.domain.education.marketpractice.dto.response;

import com.finplay.api.domain.education.marketpractice.entity.PracticeSellCause;
import com.finplay.api.domain.order.service.PracticeRunExitPlanSummaryDto;

/**
 * <b>판정 단위는 attempt의 현재 실행 세대다.</b> 재시작하면 run이 올라가 이전 세대의 예약 행이 조회 범위에서
 * 빠지므로 <b>초기화 코드 없이</b> 세 값이 전부 {@code false}·{@code null}로 돌아간다(052 EXITFREE-021 —
 * 새 컬럼을 만들지 않기로 한 이유가 이것이다).
 *
 * <p><b>수동 매도는 어느 쪽으로도 세지 않는다.</b> 이 튜토리얼이 가르치려는 것은 "미리 정해 둔 규칙이 나 대신
 * 실행된다"이며, 손해를 보고 직접 판 것은 그 경험이 아니다. 그래서 판정 근거는 예약의 최종 상태
 * ({@code FILLED_STOP_LOSS}·{@code FILLED_TAKE_PROFIT}) 하나뿐이다.
 *
 * <p><b>서버는 판정만 하고 강제하지 않는다</b>(052 확정 결정 1). 화면 잠금·자동 재매수·주문 거부는 서버가
 * 하지 않으며, 배너의 표현과 노출 위치는 프론트가 정한다.
 *
 * @param recommendedNext 먼저 겪은 쪽의 <b>반대</b>. 아직 아무것도 겪지 않았거나 둘 다 겪었으면 {@code null}
 *                        이다. 값은 {@code STOP_LOSS}·{@code TAKE_PROFIT} 둘뿐이고 {@code MANUAL}은 나오지
 *                        않는다 — 열거형을 새로 만들지 않고 {@link PracticeSellCause}를 재사용하는 이유는
 *                        {@code entries[].sellCause}와 <b>같은 문자열</b>이어야 화면이 두 값을 같은
 *                        어휘로 다룰 수 있기 때문이다
 */
public record PracticeExitExperienceResponse(
	boolean stopLossExperienced,
	boolean takeProfitExperienced,
	boolean bothExperienced,
	PracticeSellCause recommendedNext) {

	private static final PracticeExitExperienceResponse NONE = new PracticeExitExperienceResponse(false, false, false,
		null);

	/** 판정할 실행 세대가 없는 경로(attempt 없음·종목 미선택·legacy chain)의 값. {@code null}을 내려보내지 않는다. */
	public static PracticeExitExperienceResponse none() {
		return NONE;
	}

	public static PracticeExitExperienceResponse from(PracticeRunExitPlanSummaryDto summary) {
		boolean stopLoss = summary.stopLossFilled();
		boolean takeProfit = summary.takeProfitFilled();
		return new PracticeExitExperienceResponse(
			stopLoss, takeProfit, stopLoss && takeProfit, recommendedNext(stopLoss, takeProfit));
	}

	// 순서는 대본이 손절 먼저로 고정돼 있지만(052 EXITFREE-023), 좁은 익절 폭을 건 사용자는 익절이 먼저
	// 닿는다 — 그래서 "손절 → 익절"이 아니라 **먼저 겪은 쪽의 반대**로 일반화한다(확정 결정 3).
	private static PracticeSellCause recommendedNext(boolean stopLoss, boolean takeProfit) {
		if (stopLoss == takeProfit) {
			return null;
		}
		return stopLoss ? PracticeSellCause.TAKE_PROFIT : PracticeSellCause.STOP_LOSS;
	}
}
