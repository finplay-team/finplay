// 코인 튜토리얼 가상 가격 세션의 tick 진행에 맞춰 세션 귀속 PENDING 주문을 체결·취소하고, attempt/run 기반 현재 run의 PENDING 주문도 정산하는 서비스
package com.finplay.api.order.service;

import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.repository.ExitPlanRepository;
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
	private final ExitPlanRepository exitPlanRepository;
	private final LimitOrderFillService limitOrderFillService;
	private final LimitOrderCancelService limitOrderCancelService;
	private final ExitPlanFillService exitPlanFillService;
	private final ExitPlanCancelService exitPlanCancelService;

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

	/**
	 * 현재 실행 세대의 PENDING 지정가 주문과 OCO 예약을 <b>같은 시점 가격으로</b> 정산한다.
	 *
	 * <p><b>순서는 지정가 → OCO로 고정한다</b>(042 plan §tick 정산). 튜토리얼 흐름에서 둘이 동시에 걸리는
	 * 경우는 없지만, 고정해 두어야 나중에 겹칠 때 결과가 결정적이다.
	 *
	 * <p>두 진입점의 시그니처가 다르다 — 지정가는 시각을, OCO는 가격을 받는다. 041의 tick이 진행 후
	 * canonical price를 이미 손에 들고 있으므로 그 값을 그대로 넘겨 <b>차트와 체결이 같은 값을 쓴다</b>
	 * (039 TUTORIAL-FLOW-011). 중복 tick 방어는 두 {@code fillIfPending}이 PENDING일 때만 체결하는 성질에
	 * 기댄다 — 같은 가상 분에 tick이 두 번 와도 첫 번째에서 terminal이 된 건은 두 번째에 잡히지 않는다.
	 */
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

	/**
	 * 현재 실행 세대의 PENDING OCO 예약을 전부 취소하고 예약 수량을 되돌린다(042 EXITPRESET-015·016).
	 *
	 * <p>재시작 정리와 튜토리얼 매도 접수가 함께 쓴다. <b>예약이 남아 있으면 매도가 불가능하다</b> —
	 * 예약이 {@code holding.reserveQuantity()}로 수량을 잡으므로 전량 예약 상태에서는 availableQuantity가
	 * 0이라 보상 매도도 사용자 매도도 거부된다.
	 *
	 * <p><b>부분 예약 반환은 하지 않는다.</b> 튜토리얼은 전량 매수 → 전량 매도 흐름이고, 부분 매도를
	 * 지원하면 남은 수량에 대한 예약을 다시 만들어야 해서 상태가 급격히 복잡해진다.
	 */
	@Transactional
	public void cancelCurrentRunExitPlans(Long userId, Long attemptId, long runNumber) {
		for (Long exitPlanId : exitPlanRepository.findPendingPracticeRunExitPlanIds(attemptId, runNumber)) {
			exitPlanCancelService.cancel(userId, exitPlanId);
		}
	}

	private boolean isTriggered(Order order, BigDecimal price) {
		return order.getSide() == OrderSide.BUY
			? order.getLimitPrice().compareTo(price) >= 0
			: order.getLimitPrice().compareTo(price) <= 0;
	}
}
