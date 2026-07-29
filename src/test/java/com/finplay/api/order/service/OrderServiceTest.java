// OrderService의 내 주문 목록 조회(getMyOrders) 매핑을 검증하는 단위 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import com.finplay.api.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderServiceTest {

	private static final Long USER_ID = 1L;
	private static final String IDEMPOTENCY_KEY = "idem-key-1";
	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-29T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
	private final OrderRepository orderRepository = mock(OrderRepository.class);

	private final OrderService orderService = new OrderService(orderExecutionService, orderRepository);

	@Test
	void getMyOrdersMapsRepositoryOrdersToOrderListItemResponseFields() {
		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "id", 42L);
		Order order = Order.create(
			testUser(),
			account(com.finplay.api.account.domain.Market.STOCK),
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			new BigDecimal("3"),
			IDEMPOTENCY_KEY,
			"h".repeat(64),
			NOW);
		ReflectionTestUtils.setField(order, "id", 100L);
		when(orderRepository.findAllByUserIdOrderByRequestedAtDescIdDesc(USER_ID)).thenReturn(List.of(order));

		List<OrderListItemResponse> responses = orderService.getMyOrders(USER_ID);

		assertThat(responses).hasSize(1);
		OrderListItemResponse response = responses.get(0);
		assertThat(response.orderId()).isEqualTo(100L);
		assertThat(response.market()).isEqualTo("STOCK");
		assertThat(response.instrumentId()).isEqualTo(42L);
		assertThat(response.side()).isEqualTo("BUY");
		assertThat(response.orderType()).isEqualTo("MARKET");
		assertThat(response.status()).isEqualTo("FILLED");
		assertThat(response.quantity()).isEqualByComparingTo(new BigDecimal("3"));
		assertThat(response.requestedAt()).isEqualTo(NOW);
	}

	@Test
	void getMyOrdersReturnsEmptyListWhenUserHasNoOrders() {
		when(orderRepository.findAllByUserIdOrderByRequestedAtDescIdDesc(USER_ID)).thenReturn(List.of());

		List<OrderListItemResponse> responses = orderService.getMyOrders(USER_ID);

		assertThat(responses).isEmpty();
	}

	private static Instrument stockInstrument() {
		return Instrument.create(Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, NOW);
	}

	private static Account account(com.finplay.api.account.domain.Market market) {
		User user = testUser();
		return Account.create(user, market, NOW);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}
}
