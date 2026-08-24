// order 도메인이 튜토리얼 attempt 잠금·진입 BUY snapshot·자동 예약 여부를 요청하는 애플리케이션 포트
package com.finplay.api.domain.order.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import java.time.LocalDateTime;
import java.util.Optional;

public interface PracticeOrderAttributionPort {

	/**
	 * @param orderType 이 주문의 체결 방식(시장가·지정가). 구현이 049 ORDERBASICS-015 단계 순서 게이트
	 *                  판정에 쓴다 — 대본을 쓰는 튜토리얼 실행에서 시장가 왕복을 마치기 전 지정가를 걸면
	 *                  {@code PRACTICE_STAGE_LOCKED}로 거부한다.
	 */
	Optional<PracticeOrderAttributionDto> lockForOrder(Long userId, Instrument instrument, OrderType orderType);

	PracticeOrderFillContextDto lockForFill(
		PracticeOrderFillAttributionDto attribution, LocalDateTime pricedAt);

	/**
	 * BUY 체결마다 불린다. <b>진입당 1회만</b> snapshot을 만드는 판정은 구현이 한다(042 EXITPRESET-020) —
	 * 보유 중 추가 매수는 체결만 되고 기준선이 움직이지 않는다. 이름이 {@code First}였던 것을 바꾼 이유는
	 * 재진입(손절 후 재매수)에서도 새 snapshot이 생기기 때문이다.
	 */
	void createRiskSnapshotOnBuyFill(Order order, Trade trade, LocalDateTime createdAt);

	/**
	 * 이 attempt·실행 세대의 튜토리얼 예약을 <b>042 자동 예약 경로가 관리하는가.</b> 참이면 사용자가
	 * {@code DELETE /api/exit-plans/{id}}로 취소할 수 없다 — 그 예약은 tick 정산·재시작·매도 접수가
	 * 관리하는 것이라 밖에서 풀면 "보유는 있는데 기준선이 없는" 상태가 남는다(042 EXITPRESET-015·016).
	 *
	 * <p><b>거짓이면 그 예약은 사용자가 직접 만든 것이다</b>(052 EXITFREE-020) — 자동 생성 판정이
	 * "대본을 쓰지 않는 실행"에서만 참이므로, 대본을 쓰는 실행의 attempt 귀속 예약은 전부 사용자 주도다.
	 * 두 경로는 같은 실행에 공존하지 않으므로(대본 식별자로 갈린다) 이 하나의 질문으로 갈린다.
	 *
	 * @param practiceAttemptRunNumber 예약이 귀속된 실행 세대. attempt의 현재 세대와 다르면 참을 준다 —
	 *                                 지난 세대의 예약은 재시작 경로가 정리할 몫이다
	 */
	boolean managesAutomaticExitPlans(Long practiceAttemptId, Long practiceAttemptRunNumber);

}
