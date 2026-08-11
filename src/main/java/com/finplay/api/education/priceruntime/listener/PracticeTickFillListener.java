// 코인 가상 가격 세션의 tick 진행 이벤트를 받아 세션 귀속 교육 지정가 주문 체결·취소를 order 공개 서비스에 위임하는 리스너
package com.finplay.api.education.priceruntime.listener;

import com.finplay.api.education.priceruntime.event.PracticePriceTickAdvancedEvent;
import com.finplay.api.order.service.PracticeOrderSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PracticeTickFillListener {

	private final PracticeOrderSettlementService practiceOrderSettlementService;

	// 일반 동기 리스너다(@TransactionalEventListener 아님) — 발행 즉시(tick 트랜잭션 안에서) 처리해야
	// tick 99의 "체결 판정 → 잔여 취소·예약 반환 → 세션 COMPLETED 전이" 순서가 지켜진다(plan.md).
	// LimitOrderTriggerListener와 달리 예외를 삼키지 않는다 — 실패하면 tick 트랜잭션 전체가 롤백돼야 한다.
	@EventListener
	public void onTickAdvanced(PracticePriceTickAdvancedEvent event) {
		practiceOrderSettlementService.settleOnTick(event.sessionId(), event.price(), event.lastTick());
	}
}
