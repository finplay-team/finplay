// 코인 튜토리얼 가상 가격 세션의 tick 진행에 맞춰 세션 귀속 PENDING 주문을 체결·취소하고, attempt/run 기반 현재 run의 PENDING 주문도 정산하는 서비스
package com.finplay.api.order.service;

import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
	// attempt 귀속 주문은 ID 목록만 비잠금 조회한 뒤 fill 서비스가 attempt → order → account → holding 순으로
	// 잠근다. 기존 세션 가격만 쓰는 무귀속 주문은 같은 목록에서 기존 조건 판정을 유지한다.
	@Transactional
	public void settleOnTick(Long sessionId, BigDecimal price, boolean lastTick) {
		List<Long> pendingOrderIds = orderRepository.findPendingIdsBySessionId(sessionId);

		// attempt 귀속 주문은 fill 서비스가 attempt를 먼저 잠그고 canonical 가격으로 조건을 판정한다.
		for (Long orderId : pendingOrderIds) {
			Order order = orderRepository.findById(orderId)
				.orElseThrow(() -> new IllegalStateException("교육 지정가 주문을 찾을 수 없습니다."));
			if (order.getPracticeAttemptId() != null || isTriggered(order, price)) {
				limitOrderFillService.fillIfPending(orderId);
			}
		}

		if (!lastTick) {
			return;
		}
		// fillIfPending은 같은 트랜잭션의 영속성 컨텍스트에서 동일 엔티티를 반환하므로 getStatus()가
		// 방금 체결 여부를 그대로 반영한다 — 체결된 주문은 취소 대상에서 자연히 제외된다.
		for (Long orderId : orderRepository.findPendingIdsBySessionId(sessionId)) {
			Long userId = orderRepository.findById(orderId)
				.orElseThrow(() -> new IllegalStateException("교육 지정가 주문을 찾을 수 없습니다."))
				.getUser().getId();
			limitOrderCancelService.cancelOrder(userId, orderId);
		}
	}

	@Transactional
	public void settleCurrentRun(Long attemptId, long runNumber, LocalDateTime pricedAt) {
		for (Long orderId : orderRepository.findPendingPracticeRunOrderIds(attemptId, runNumber)) {
			limitOrderFillService.fillIfPending(orderId, pricedAt);
		}
	}

	private boolean isTriggered(Order order, BigDecimal price) {
		return order.getSide() == OrderSide.BUY
			? order.getLimitPrice().compareTo(price) >= 0
			: order.getLimitPrice().compareTo(price) <= 0;
	}
}
