// PracticeOrderSettlementService.settleOnTick의 세션 PENDING 주문 일괄 잠금·체결 판정·마지막 tick 취소를 검증하는 단위 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
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
import com.finplay.api.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeOrderSettlementServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 10, 0);
	private static final Long SESSION_ID = 100L;
	private static final Long USER_ID = 1L;

	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final LimitOrderFillService limitOrderFillService = mock(LimitOrderFillService.class);
	private final LimitOrderCancelService limitOrderCancelService = mock(LimitOrderCancelService.class);

	private final PracticeOrderSettlementService service = new PracticeOrderSettlementService(orderRepository,
		limitOrderFillService, limitOrderCancelService);

	@Test
	void settleOnTickFillsOnlyOrdersWhoseLimitPriceIsAtOrAboveCurrentPrice() {
		Order fillable = practiceOrder(1L, "10000");
		Order notFillable = practiceOrder(2L, "9000");
		when(orderRepository.findPendingBySessionIdForUpdate(SESSION_ID))
			.thenReturn(List.of(fillable, notFillable));

		service.settleOnTick(SESSION_ID, new BigDecimal("9500"), false);

		verify(limitOrderFillService).fillIfPending(fillable.getId());
		verify(limitOrderFillService, never()).fillIfPending(notFillable.getId());
	}

	@Test
	void settleOnTickDoesNotCancelAnyOrderWhenNotLastTick() {
		Order pending = practiceOrder(3L, "9000");
		when(orderRepository.findPendingBySessionIdForUpdate(SESSION_ID)).thenReturn(List.of(pending));

		service.settleOnTick(SESSION_ID, new BigDecimal("9500"), false);

		verify(limitOrderCancelService, never()).cancelOrder(eq(USER_ID), eq(pending.getId()));
	}

	@Test
	void settleOnTickJudgesFillFirstThenCancelsOnlyRemainingPendingOrdersOnLastTick() {
		Order fillsAtLastTick = practiceOrder(4L, "10000");
		Order staysPending = practiceOrder(5L, "9000");
		when(orderRepository.findPendingBySessionIdForUpdate(SESSION_ID))
			.thenReturn(List.of(fillsAtLastTick, staysPending));
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
		when(orderRepository.findPendingBySessionIdForUpdate(SESSION_ID)).thenReturn(List.of());

		service.settleOnTick(SESSION_ID, new BigDecimal("9500"), true);

		verify(orderRepository).findPendingBySessionIdForUpdate(SESSION_ID);
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
