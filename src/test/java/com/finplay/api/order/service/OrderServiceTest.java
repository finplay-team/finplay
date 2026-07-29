// OrderService의 멱등성 오케스트레이션(createOrder)과 내 주문 목록 조회(getMyOrders) 매핑을 검증하는 단위 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class OrderServiceTest {

	private static final Long USER_ID = 1L;
	private static final String IDEMPOTENCY_KEY = "idem-key-1";
	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-29T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final TradeRepository tradeRepository = mock(TradeRepository.class);

	private final OrderService orderService = new OrderService(
		orderExecutionService, orderRepository, tradeRepository);

	@Test
	void createOrderReturnsReconstructedResponseWhenSameKeyAndSameBodyIsReplayed() {
		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "id", 42L);
		Order existingOrder = Order.create(
			testUser(),
			account(com.finplay.api.account.domain.Market.STOCK),
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			new BigDecimal("3"),
			IDEMPOTENCY_KEY,
			requestHashOf(sampleRequest()),
			NOW);
		ReflectionTestUtils.setField(existingOrder, "id", 100L);
		Trade existingTrade = Trade.of(
			existingOrder, existingOrder.getAccount(), instrument, OrderSide.BUY, new BigDecimal("100"),
			new BigDecimal("3"), 300L, 1L, null, NOW, NOW);
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.of(existingOrder));
		when(tradeRepository.findByOrderId(100L)).thenReturn(Optional.of(existingTrade));

		OrderResponse response = orderService.createOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest());

		assertThat(response).isEqualTo(OrderResponse.of(existingOrder, existingTrade));
		verifyNoInteractions(orderExecutionService);
	}

	@Test
	void createOrderThrowsIdempotencyConflictWhenSameKeyButDifferentBodyIsReplayed() {
		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "id", 42L);
		Order existingOrder = Order.create(
			testUser(),
			account(com.finplay.api.account.domain.Market.STOCK),
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			new BigDecimal("3"),
			IDEMPOTENCY_KEY,
			"different-hash",
			NOW);
		ReflectionTestUtils.setField(existingOrder, "id", 100L);
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.of(existingOrder));

		assertThatThrownBy(() -> orderService.createOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest()))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
		verifyNoInteractions(orderExecutionService);
	}

	@Test
	void createOrderExecutesAndReturnsResultWhenIdempotencyKeyIsNew() {
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
		OrderResponse executionResult = new OrderResponse(
			200L, "STOCK", 42L, "BUY", "MARKET", "FILLED", new BigDecimal("3"), NOW, 300L, new BigDecimal("100"),
			300L, 1L, null, NOW);
		when(orderExecutionService.execute(any(), anyString(), anyString(), any())).thenReturn(executionResult);

		OrderResponse response = orderService.createOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest());

		assertThat(response).isEqualTo(executionResult);
		verify(orderExecutionService).execute(any(), anyString(), anyString(), any());
	}

	@Test
	void createOrderReturnsReconstructedResponseWhenExecuteHitsConcurrentUniqueConstraintButReplayIsFound() {
		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "id", 42L);
		Order existingOrder = Order.create(
			testUser(),
			account(com.finplay.api.account.domain.Market.STOCK),
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			new BigDecimal("3"),
			IDEMPOTENCY_KEY,
			requestHashOf(sampleRequest()),
			NOW);
		ReflectionTestUtils.setField(existingOrder, "id", 100L);
		Trade existingTrade = Trade.of(
			existingOrder, existingOrder.getAccount(), instrument, OrderSide.BUY, new BigDecimal("100"),
			new BigDecimal("3"), 300L, 1L, null, NOW, NOW);
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.empty(), Optional.of(existingOrder));
		when(tradeRepository.findByOrderId(100L)).thenReturn(Optional.of(existingTrade));
		when(orderExecutionService.execute(any(), anyString(), anyString(), any()))
			.thenThrow(new DataIntegrityViolationException("동시 경합으로 유니크 제약 위반"));

		OrderResponse response = orderService.createOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest());

		assertThat(response).isEqualTo(OrderResponse.of(existingOrder, existingTrade));
		verify(orderExecutionService).execute(any(), anyString(), anyString(), any());
	}

	@Test
	void createOrderThrowsIdempotencyConflictWhenExecuteHitsConcurrentUniqueConstraintAndReplayIsNotFound() {
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
		when(orderExecutionService.execute(any(), anyString(), anyString(), any()))
			.thenThrow(new DataIntegrityViolationException("동시 경합으로 유니크 제약 위반"));

		assertThatThrownBy(() -> orderService.createOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest()))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
	}

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

	private static OrderCreateRequest sampleRequest() {
		return new OrderCreateRequest(Market.STOCK, 42L, OrderSide.BUY, "MARKET", new BigDecimal("3"));
	}

	// OrderService.calculateRequestHash(private)와 동일한 형식·알고리즘으로 테스트용 해시를 재현한다.
	private static String requestHashOf(OrderCreateRequest request) {
		String raw = "%s:%d:%s:%s:%s".formatted(
			request.market().name(),
			request.instrumentId(),
			request.side().name(),
			request.orderType(),
			request.quantity().toPlainString());
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(hashBytes);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
		}
	}
}
