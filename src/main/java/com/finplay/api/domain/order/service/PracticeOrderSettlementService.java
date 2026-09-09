package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.repository.OrderRepository;
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
	private final ExitPlanRepository exitPlanRepository;
	private final LimitOrderFillService limitOrderFillService;
	private final LimitOrderCancelService limitOrderCancelService;
	private final ExitPlanFillService exitPlanFillService;
	private final ExitPlanCancelService exitPlanCancelService;

	@Transactional
	public void settleOnTick(Long sessionId, BigDecimal price, boolean lastTick) {
		List<Long> pendingOrderIds = orderRepository.findPendingIdsBySessionId(sessionId);

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
		for (Long orderId : pendingOrderIds) {
			Order order = orderRepository.findById(orderId)
				.orElseThrow(() -> new IllegalStateException("교육 지정가 주문을 찾을 수 없습니다."));
			if (order.getStatus() != OrderStatus.PENDING) {
				continue;
			}
			limitOrderCancelService.cancelOrder(order.getUser().getId(), orderId);
		}
	}

	@Transactional
	public void settleCurrentRun(
		Long attemptId, long runNumber, LocalDateTime pricedAt, BigDecimal canonicalPrice) {
		for (Long orderId : orderRepository.findPendingPracticeRunOrderIds(attemptId, runNumber)) {
			limitOrderFillService.fillIfPending(orderId, pricedAt);
		}
		if (canonicalPrice == null) {
			return;
		}
		for (Long exitPlanId : exitPlanRepository.findPendingPracticeRunExitPlanIds(attemptId, runNumber)) {
			exitPlanFillService.fillIfPending(exitPlanId, canonicalPrice);
		}
	}

	@Transactional
	public void cancelCurrentRunExitPlans(Long userId, Long attemptId, long runNumber) {
		for (Long exitPlanId : exitPlanRepository.findPendingPracticeRunExitPlanIds(attemptId, runNumber)) {
			exitPlanCancelService.cancel(userId, exitPlanId);
		}
	}

	@Transactional
	public void cancelCurrentRunPendingLimitOrders(Long userId, Long attemptId, long runNumber) {
		for (Long orderId : orderRepository.findPendingPracticeRunOrderIds(attemptId, runNumber)) {
			limitOrderCancelService.cancelOrder(userId, orderId);
		}
	}

	private boolean isTriggered(Order order, BigDecimal price) {
		return order.getSide() == OrderSide.BUY
			? order.getLimitPrice().compareTo(price) >= 0
			: order.getLimitPrice().compareTo(price) <= 0;
	}
}
