// 튜토리얼 자동 예약(042)이 엔진에 넘기는 attempt 귀속과 대본 기준가 snapshot
package com.finplay.api.domain.order.service;

import java.math.BigDecimal;

/**
 * 이 record가 non-null이면 튜토리얼 자동 예약 경로다. 일반 경로·교육 경로와 셋 다 배타적이며, 엔진은
 * 귀속 값을 해석하지 않고 plan에 그대로 저장한다.
 *
 * <p><b>{@code baselinePrice}를 호출부가 주입한다.</b> 엔진 기본 경로는
 * {@code priceQueryService.getPrice}로 baseline을 확정하는데, 그것은 튜토리얼 샘플 종목이면 031이 만든
 * 주기 180초·진폭 ±3%의 <b>벽시계 사인파</b>로 분기한다. 대본과 아무 관계 없는 값이
 * {@code baseline_price}에 영속되고 039 TUTORIAL-FLOW-011("화면 현재가·체결 판정·tick 정산이 같은 값")과
 * 어긋난다. 매수가 실패하지는 않지만 거짓 데이터가 남는다(042 plan §자동 예약 생성).
 */
public record ExitPlanPracticeOriginDto(Long attemptId, long runNumber, BigDecimal baselinePrice) {

	public ExitPlanPracticeOriginDto {
		if (attemptId == null || runNumber <= 0) {
			throw new IllegalArgumentException("튜토리얼 자동 예약은 attemptId와 양의 runNumber가 필요합니다.");
		}
		if (baselinePrice == null) {
			throw new IllegalArgumentException("튜토리얼 자동 예약은 대본 기준가(baselinePrice)가 필요합니다.");
		}
	}
}
