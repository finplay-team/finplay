// 코인 튜토리얼 가상 가격 세션의 tick 진행에 맞춰 세션 귀속 PENDING 주문을 체결·취소하는 서비스
package com.finplay.api.order.service;

import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeOrderSettlementService {

	private final OrderRepository orderRepository;
	private final LimitOrderFillService limitOrderFillService;
	private final LimitOrderCancelService limitOrderCancelService;

	// education의 PracticeTickFillListener가 tick 진행 트랜잭션 안에서 동기 호출한다(plan.md "트랜잭션·잠금·이벤트").
	// 잠금 순서: session(호출부가 이미 잠금) → order(id ASC 일괄 FOR UPDATE, 아래) → account → (해당 없음) holding.
	// 세션 PENDING 주문을 먼저 전부 잠근 뒤에만 체결·취소 과정에서 account를 잠그며, 순차 잠금(1건씩 잠갔다 풀기)은
	// order↔account 교착 위험이 있어 쓰지 않는다.
	@Transactional
	public void settleOnTick(Long sessionId, BigDecimal price, boolean lastTick) {
		List<Order> pendingOrders = orderRepository.findPendingBySessionIdForUpdate(sessionId);

		// tick 99에서도 체결 판정을 먼저 수행한 뒤 잔여만 취소한다(spec COIN-PRICE-RUNTIME-008).
		for (Order order : pendingOrders) {
			if (order.getLimitPrice().compareTo(price) >= 0) {
				limitOrderFillService.fillIfPending(order.getId());
			}
		}

		if (!lastTick) {
			return;
		}
		// fillIfPending은 같은 트랜잭션의 영속성 컨텍스트에서 동일 엔티티를 반환하므로 getStatus()가
		// 방금 체결 여부를 그대로 반영한다 — 체결된 주문은 취소 대상에서 자연히 제외된다.
		for (Order order : pendingOrders) {
			if (order.getStatus() == OrderStatus.PENDING) {
				limitOrderCancelService.cancelOrder(order.getUser().getId(), order.getId());
			}
		}
	}
}
