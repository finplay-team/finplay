// PracticeOrderSettlementService.settleOnTick의 세션 PENDING 주문 일괄 잠금·체결 판정·마지막 tick 취소를 검증하는 단위 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.repository.ExitPlanRepository;
import com.finplay.api.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeOrderSettlementServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 10, 0);
	private static final Long SESSION_ID = 100L;
	private static final Long USER_ID = 1L;

	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final LimitOrderFillService limitOrderFillService = mock(LimitOrderFillService.class);
	private final LimitOrderCancelService limitOrderCancelService = mock(LimitOrderCancelService.class);

	private final ExitPlanRepository exitPlanRepository = mock(ExitPlanRepository.class);
	private final ExitPlanFillService exitPlanFillService = mock(ExitPlanFillService.class);
	private final ExitPlanCancelService exitPlanCancelService = mock(ExitPlanCancelService.class);

	private final PracticeOrderSettlementService service = new PracticeOrderSettlementService(orderRepository,
		exitPlanRepository, limitOrderFillService, limitOrderCancelService, exitPlanFillService,
		exitPlanCancelService);

	@Test
	void settleOnTickFillsOnlyOrdersWhoseLimitPriceIsAtOrAboveCurrentPrice() {
		Order fillable = practiceOrder(1L, "10000");
		Order notFillable = practiceOrder(2L, "9000");
		when(orderRepository.findPendingIdsBySessionId(SESSION_ID))
			.thenReturn(List.of(fillable.getId(), notFillable.getId()));
		when(orderRepository.findById(fillable.getId())).thenReturn(java.util.Optional.of(fillable));
		when(orderRepository.findById(notFillable.getId())).thenReturn(java.util.Optional.of(notFillable));

		service.settleOnTick(SESSION_ID, new BigDecimal("9500"), false);

		verify(limitOrderFillService).fillIfPending(fillable.getId());
		verify(limitOrderFillService, never()).fillIfPending(notFillable.getId());
	}

	@Test
	void settleOnTickDoesNotCancelAnyOrderWhenNotLastTick() {
		Order pending = practiceOrder(3L, "9000");
		when(orderRepository.findPendingIdsBySessionId(SESSION_ID)).thenReturn(List.of(pending.getId()));
		when(orderRepository.findById(pending.getId())).thenReturn(java.util.Optional.of(pending));

		service.settleOnTick(SESSION_ID, new BigDecimal("9500"), false);

		verify(limitOrderCancelService, never()).cancelOrder(eq(USER_ID), eq(pending.getId()));
	}

	@Test
	void settleOnTickJudgesFillFirstThenCancelsOnlyRemainingPendingOrdersOnLastTick() {
		Order fillsAtLastTick = practiceOrder(4L, "10000");
		Order staysPending = practiceOrder(5L, "9000");
		when(orderRepository.findPendingIdsBySessionId(SESSION_ID))
			.thenReturn(List.of(fillsAtLastTick.getId(), staysPending.getId()), List.of(staysPending.getId()));
		when(orderRepository.findById(fillsAtLastTick.getId())).thenReturn(java.util.Optional.of(fillsAtLastTick));
		when(orderRepository.findById(staysPending.getId())).thenReturn(java.util.Optional.of(staysPending));
		// fillIfPending은 실제 서비스에서 같은 영속성 컨텍스트의 엔티티를 체결 확정한다 — 단위 테스트에서는
		// 호출 시 markFilled()를 직접 실행해 이후 getStatus() 판정이 그 결과를 반영하도록 흉내낸다.
		doAnswer(invocation -> {
			fillsAtLastTick.markFilled();
			return null;
		}).when(limitOrderFillService).fillIfPending(fillsAtLastTick.getId());

		service.settleOnTick(SESSION_ID, new BigDecimal("9500"), true);

		verify(limitOrderFillService).fillIfPending(fillsAtLastTick.getId());
		verify(limitOrderCancelService).cancelOrder(USER_ID, staysPending.getId());
		verify(limitOrderCancelService, never()).cancelOrder(eq(USER_ID), eq(fillsAtLastTick.getId()));
		assertThat(fillsAtLastTick.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(staysPending.getStatus()).isEqualTo(OrderStatus.PENDING); // 취소는 별도 서비스가 상태를 바꾼다(mock)
	}

	@Test
	void settleOnTickLoadsPendingOrdersScopedToGivenSessionOnly() {
		when(orderRepository.findPendingIdsBySessionId(SESSION_ID)).thenReturn(List.of());

		service.settleOnTick(SESSION_ID, new BigDecimal("9500"), true);

		verify(orderRepository, org.mockito.Mockito.times(1)).findPendingIdsBySessionId(SESSION_ID);
	}

	// PR #514 재리뷰 권장사항 — 취소 판정을 위해 findPendingIdsBySessionId를 다시 부르지 않고 체결 판정
	// 단계에서 이미 읽어둔 pendingOrderIds를 재사용한다. 재조회했다면 advanceTick이 READ COMMITTED가 된
	// 뒤로 이 틱 처리 도중 같은 세션에 새로 커밋된 지정가 주문까지 취소 대상에 끼어들 수 있었다 — 이
	// 테스트는 그 재조회 자체가 사라졌음을(정확히 1회만 호출됨) 검증해 그 경로를 원천적으로 막는다.
	@Test
	void settleOnTickCancelLoopReusesInitiallyFetchedIdsInsteadOfRequeryingSoLaterCommittedOrdersAreUnaffected() {
		Order staysPending = practiceOrder(6L, "9000");
		when(orderRepository.findPendingIdsBySessionId(SESSION_ID)).thenReturn(List.of(staysPending.getId()));
		when(orderRepository.findById(staysPending.getId())).thenReturn(java.util.Optional.of(staysPending));

		service.settleOnTick(SESSION_ID, new BigDecimal("9500"), true);

		verify(orderRepository, org.mockito.Mockito.times(1)).findPendingIdsBySessionId(SESSION_ID);
		verify(limitOrderCancelService).cancelOrder(USER_ID, staysPending.getId());
	}

	@Test
	void settleCurrentRunPassesSameCanonicalPricingTimeToEveryPendingOrder() {
		when(orderRepository.findPendingPracticeRunOrderIds(11L, 3L)).thenReturn(List.of(1L, 2L));

		service.settleCurrentRun(11L, 3L, NOW, new BigDecimal("10000"));

		verify(limitOrderFillService).fillIfPending(1L, NOW);
		verify(limitOrderFillService).fillIfPending(2L, NOW);
	}

	// 042 EXITPRESET-014 — 지정가 → OCO 순서를 고정한다. 튜토리얼 흐름에서 둘이 동시에 걸리는 경우는
	// 없지만, 순서가 정해져 있어야 나중에 겹칠 때 결과가 결정적이다.
	@Test
	void settleCurrentRunFillsLimitOrdersBeforeExitPlansWithTheSameCanonicalPrice() {
		BigDecimal canonicalPrice = new BigDecimal("9750.00000000");
		when(orderRepository.findPendingPracticeRunOrderIds(11L, 3L)).thenReturn(List.of(1L));
		when(exitPlanRepository.findPendingPracticeRunExitPlanIds(11L, 3L)).thenReturn(List.of(7L));

		service.settleCurrentRun(11L, 3L, NOW, canonicalPrice);

		InOrder inOrder = inOrder(limitOrderFillService, exitPlanFillService);
		inOrder.verify(limitOrderFillService).fillIfPending(1L, NOW);
		inOrder.verify(exitPlanFillService).fillIfPending(7L, canonicalPrice);
	}

	@Test
	void cancelCurrentRunExitPlansCancelsEveryPendingPlanOfThatRun() {
		when(exitPlanRepository.findPendingPracticeRunExitPlanIds(11L, 3L)).thenReturn(List.of(7L, 8L));

		service.cancelCurrentRunExitPlans(USER_ID, 11L, 3L);

		verify(exitPlanCancelService).cancel(USER_ID, 7L);
		verify(exitPlanCancelService).cancel(USER_ID, 8L);
	}

	private Order practiceOrder(long orderId, String limitPrice) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), 0L, true, NOW);
		User user = User.create("trader@finplay.com", "hash", "trader", NOW);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		Account account = Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, NOW);
		Order order = Order.createPracticeLimitPendingBuy(
			user, account, instrument, new BigDecimal("0.1"), new BigDecimal(limitPrice), SESSION_ID,
			"idem-" + orderId, "h".repeat(64), NOW);
		ReflectionTestUtils.setField(order, "id", orderId);
		return order;
	}
}
