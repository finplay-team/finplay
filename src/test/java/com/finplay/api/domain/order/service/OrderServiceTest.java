// OrderService의 멱등성 오케스트레이션(createOrder)과 내 주문 목록 조회(getMyOrders) 매핑을 검증하는 단위 테스트다.
package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderListItemResponse;
import com.finplay.api.domain.order.dto.response.OrderListResponse;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
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
	private final AccountService accountService = mock(AccountService.class);

	private final OrderService orderService = new OrderService(
		orderExecutionService, orderRepository, tradeRepository, accountService);

	@Test
	void createOrderReturnsReconstructedResponseWhenSameKeyAndSameBodyIsReplayed() {
		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "id", 42L);
		Order existingOrder = Order.create(
			testUser(),
			account(Market.STOCK),
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			new BigDecimal("3"),
			IDEMPOTENCY_KEY,
			requestHashOf(sampleRequest()),
			NOW);
		ReflectionTestUtils.setField(existingOrder, "id", 100L);
		Trade existingTrade = Trade.of(
			existingOrder, existingOrder.getAccount(), instrument,
			com.finplay.api.domain.market.entity.StockReplaySession.ready(NOW.toLocalDate(), NOW.toLocalDate(), NOW,
				NOW),
			OrderSide.BUY, new BigDecimal("100"),
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
			account(Market.STOCK),
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
			account(Market.STOCK),
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			new BigDecimal("3"),
			IDEMPOTENCY_KEY,
			requestHashOf(sampleRequest()),
			NOW);
		ReflectionTestUtils.setField(existingOrder, "id", 100L);
		Trade existingTrade = Trade.of(
			existingOrder, existingOrder.getAccount(), instrument,
			com.finplay.api.domain.market.entity.StockReplaySession.ready(NOW.toLocalDate(), NOW.toLocalDate(), NOW,
				NOW),
			OrderSide.BUY, new BigDecimal("100"),
			new BigDecimal("3"), 300L, 1L, null, NOW, NOW);
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.empty(), Optional.of(existingOrder));
		when(tradeRepository.findByOrderId(100L)).thenReturn(Optional.of(existingTrade));
		when(orderExecutionService.execute(any(), anyString(), anyString(), any()))
			.thenThrow(new DataIntegrityViolationException(
				"Duplicate entry '1-idem-key-1' for key 'orders.uk_orders_user_idempotency'"));

		OrderResponse response = orderService.createOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest());

		assertThat(response).isEqualTo(OrderResponse.of(existingOrder, existingTrade));
		verify(orderExecutionService).execute(any(), anyString(), anyString(), any());
	}

	@Test
	void createOrderThrowsIdempotencyConflictWhenExecuteHitsConcurrentUniqueConstraintAndReplayIsNotFound() {
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
		when(orderExecutionService.execute(any(), anyString(), anyString(), any()))
			.thenThrow(new DataIntegrityViolationException(
				"Duplicate entry '1-idem-key-1' for key 'orders.uk_orders_user_idempotency'"));

		assertThatThrownBy(() -> orderService.createOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest()))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
	}

	@Test
	void createOrderRethrowsUnrelatedUniqueConstraintViolationWithoutMaskingItAsIdempotencyConflict() {
		// PR #93 리뷰 권장사항: holdings 등 다른 유니크 제약 위반까지 멱등키 충돌로 잘못 판단하면 안 된다.
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
		DataIntegrityViolationException holdingsConstraintViolation = new DataIntegrityViolationException(
			"Duplicate entry '10-42' for key 'holdings.uk_holdings_account_instrument'");
		when(orderExecutionService.execute(any(), anyString(), anyString(), any()))
			.thenThrow(holdingsConstraintViolation);

		assertThatThrownBy(() -> orderService.createOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest()))
			.isSameAs(holdingsConstraintViolation);

		// 원인이 다른 제약이므로 재조회(멱등키 폴백)를 시도하지 않는다 — findByUserIdAndIdempotencyKey는 선제 조회 1회만 호출됨.
		verify(orderRepository, times(1)).findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY);
	}

	// ADR-0028 — 데드락 1회 재시도(PR #514 리뷰 권장사항 1번).
	@Test
	void createOrderRetriesOnceAndReturnsResultWhenDeadlockThenSucceeds() {
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
		OrderResponse executionResult = new OrderResponse(
			200L, "STOCK", 42L, "BUY", "MARKET", "FILLED", new BigDecimal("3"), NOW, 300L, new BigDecimal("100"),
			300L, 1L, null, NOW);
		when(orderExecutionService.execute(any(), anyString(), anyString(), any()))
			.thenThrow(
				new CannotAcquireLockException("Deadlock found when trying to get lock; try restarting transaction"))
			.thenReturn(executionResult);

		OrderResponse response = orderService.createOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest());

		assertThat(response).isEqualTo(executionResult);
		verify(orderExecutionService, times(2)).execute(any(), anyString(), anyString(), any());
	}

	@Test
	void createOrderPropagatesDeadlockWhenRetryAlsoFails() {
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
		CannotAcquireLockException deadlock = new CannotAcquireLockException(
			"Deadlock found when trying to get lock; try restarting transaction");
		when(orderExecutionService.execute(any(), anyString(), anyString(), any())).thenThrow(deadlock);

		assertThatThrownBy(() -> orderService.createOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest()))
			.isSameAs(deadlock);
		verify(orderExecutionService, times(2)).execute(any(), anyString(), anyString(), any());
	}

	// PR #514 리뷰 권장사항 2번 — 재시도 호출도 원래 catch(DataIntegrityViolationException)와 같은 멱등키
	// 충돌 폴백을 타야 한다. 이 테스트는 executeWithIdempotencyFallback으로 재시도 호출을 감싸기 전에는
	// 실패했다(재시도에서 던진 DataIntegrityViolationException이 그대로 전파돼 IDEMPOTENCY_CONFLICT
	// 대신 원인 예외가 노출됨).
	@Test
	void createOrderFallsBackToReplayWhenRetryAfterDeadlockHitsIdempotencyConflict() {
		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "id", 42L);
		Order existingOrder = Order.create(
			testUser(),
			account(Market.STOCK),
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			new BigDecimal("3"),
			IDEMPOTENCY_KEY,
			requestHashOf(sampleRequest()),
			NOW);
		ReflectionTestUtils.setField(existingOrder, "id", 100L);
		Trade existingTrade = Trade.of(
			existingOrder, existingOrder.getAccount(), instrument,
			com.finplay.api.domain.market.entity.StockReplaySession.ready(NOW.toLocalDate(), NOW.toLocalDate(), NOW,
				NOW),
			OrderSide.BUY, new BigDecimal("100"),
			new BigDecimal("3"), 300L, 1L, null, NOW, NOW);
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.empty(), Optional.of(existingOrder));
		when(tradeRepository.findByOrderId(100L)).thenReturn(Optional.of(existingTrade));
		when(orderExecutionService.execute(any(), anyString(), anyString(), any()))
			.thenThrow(
				new CannotAcquireLockException("Deadlock found when trying to get lock; try restarting transaction"))
			.thenThrow(new DataIntegrityViolationException(
				"Duplicate entry '1-idem-key-1' for key 'orders.uk_orders_user_idempotency'"));

		OrderResponse response = orderService.createOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest());

		assertThat(response).isEqualTo(OrderResponse.of(existingOrder, existingTrade));
		verify(orderExecutionService, times(2)).execute(any(), anyString(), anyString(), any());
	}

	@Test
	void getMyOrdersMapsRepositoryOrdersToOrderListItemResponseFields() {
		Account account = account(Market.STOCK);
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.STOCK))
			.thenReturn(account);

		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "id", 42L);
		Order order = Order.create(
			testUser(),
			account,
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			new BigDecimal("3"),
			IDEMPOTENCY_KEY,
			"h".repeat(64),
			NOW);
		ReflectionTestUtils.setField(order, "id", 100L);
		when(orderRepository.findByAccountIdWithCursor(eq(10L), isNull(), isNull(), eq(21)))
			.thenReturn(List.of(order));

		OrderListResponse response = orderService.getMyOrders(
			USER_ID, Market.STOCK, null, 20);

		assertThat(response.content()).hasSize(1);
		OrderListItemResponse itemResponse = response.content().get(0);
		assertThat(itemResponse.orderId()).isEqualTo(100L);
		assertThat(itemResponse.market()).isEqualTo("STOCK");
		assertThat(itemResponse.instrumentId()).isEqualTo(42L);
		assertThat(itemResponse.side()).isEqualTo("BUY");
		assertThat(itemResponse.orderType()).isEqualTo("MARKET");
		assertThat(itemResponse.status()).isEqualTo("FILLED");
		assertThat(itemResponse.quantity()).isEqualByComparingTo(new BigDecimal("3"));
		assertThat(itemResponse.requestedAt()).isEqualTo(NOW);
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	@Test
	void getMyOrdersReturnsEmptyContentWhenAccountHasNoOrders() {
		Account account = account(Market.STOCK);
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.STOCK))
			.thenReturn(account);
		when(orderRepository.findByAccountIdWithCursor(eq(10L), isNull(), isNull(), eq(21)))
			.thenReturn(List.of());

		OrderListResponse response = orderService.getMyOrders(
			USER_ID, Market.STOCK, null, 20);

		assertThat(response.content()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	@Test
	void getMyOrdersReturnsNoNextPageWhenFetchedCountIsAtMostLimit() {
		Account account = account(Market.STOCK);
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.STOCK))
			.thenReturn(account);

		Order order1 = order(3L, NOW.minusMinutes(1));
		Order order2 = order(2L, NOW.minusMinutes(2));
		when(orderRepository.findByAccountIdWithCursor(eq(10L), isNull(), isNull(), eq(3)))
			.thenReturn(List.of(order1, order2));

		OrderListResponse response = orderService.getMyOrders(
			USER_ID, Market.STOCK, null, 2);

		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
		assertThat(response.content()).hasSize(2);
	}

	@Test
	void getMyOrdersSetsNextCursorFromLimitthItemWhenFetchedCountExceedsLimit() {
		Account account = account(Market.STOCK);
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.STOCK))
			.thenReturn(account);

		Order order1 = order(30L, NOW.minusMinutes(1));
		Order order2 = order(20L, NOW.minusMinutes(2));
		Order order3 = order(10L, NOW.minusMinutes(3));
		int limit = 2;
		when(orderRepository.findByAccountIdWithCursor(eq(10L), isNull(), isNull(), eq(limit + 1)))
			.thenReturn(List.of(order1, order2, order3));

		OrderListResponse response = orderService.getMyOrders(
			USER_ID, Market.STOCK, null, limit);

		assertThat(response.hasNext()).isTrue();
		// 다음 페이지 있음(3건 조회) 시 nextCursor는 반환 페이지(limit=2건)의 마지막 항목인 order2 기준이어야 한다 — 초과 조회된 order3 기준이면 버그.
		assertThat(response.nextCursor()).isEqualTo(OrderCursor.encode(order2));
		assertThat(response.content()).hasSize(2);
	}

	@Test
	void getMyOrdersDelegatesOwnershipAndMarketScopeValidationToAccountService() {
		Account account = account(Market.STOCK);
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.STOCK))
			.thenReturn(account);
		when(orderRepository.findByAccountIdWithCursor(eq(10L), isNull(), isNull(), eq(21)))
			.thenReturn(List.of());

		orderService.getMyOrders(USER_ID, Market.STOCK, null, 20);

		verify(accountService).getAccountFor(USER_ID, Market.STOCK);
	}

	@Test
	void getMyOrdersPropagatesExceptionThrownByCorruptedCursorWithoutQueryingRepository() {
		Account account = account(Market.STOCK);
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.STOCK))
			.thenReturn(account);

		assertThatThrownBy(() -> orderService.getMyOrders(
			USER_ID, Market.STOCK, "garbage", 20))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(orderRepository, never()).findByAccountIdWithCursor(any(), any(), any(), anyInt());
	}

	@Test
	void getMyPendingOrdersMapsRepositoryOrdersToOrderListItemResponseFieldsWithPendingStatus() {
		Account account = account(Market.CRYPTO);
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.CRYPTO))
			.thenReturn(account);

		Instrument instrument = cryptoInstrument();
		ReflectionTestUtils.setField(instrument, "id", 42L);
		Order order = Order.createLimitPending(
			testUser(),
			account,
			instrument,
			OrderSide.BUY,
			new BigDecimal("1"),
			new BigDecimal("70000000"),
			IDEMPOTENCY_KEY,
			"h".repeat(64),
			NOW);
		ReflectionTestUtils.setField(order, "id", 100L);
		when(orderRepository.findByAccountIdAndStatusWithCursor(
			eq(10L), eq(OrderStatus.PENDING), isNull(), isNull(), eq(21)))
			.thenReturn(List.of(order));

		OrderListResponse response = orderService.getMyPendingOrders(
			USER_ID, Market.CRYPTO, null, 20);

		assertThat(response.content()).hasSize(1);
		OrderListItemResponse itemResponse = response.content().get(0);
		assertThat(itemResponse.orderId()).isEqualTo(100L);
		assertThat(itemResponse.market()).isEqualTo("CRYPTO");
		assertThat(itemResponse.instrumentId()).isEqualTo(42L);
		assertThat(itemResponse.side()).isEqualTo("BUY");
		assertThat(itemResponse.orderType()).isEqualTo("LIMIT");
		assertThat(itemResponse.status()).isEqualTo("PENDING");
		assertThat(itemResponse.quantity()).isEqualByComparingTo(new BigDecimal("1"));
		assertThat(itemResponse.requestedAt()).isEqualTo(NOW);
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	@Test
	void getMyPendingOrdersQueriesRepositoryWithPendingStatusFilter() {
		Account account = account(Market.CRYPTO);
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(orderRepository.findByAccountIdAndStatusWithCursor(
			eq(10L), eq(OrderStatus.PENDING), isNull(), isNull(), eq(21)))
			.thenReturn(List.of());

		orderService.getMyPendingOrders(USER_ID, Market.CRYPTO, null, 20);

		// PENDING 고정 필터로 리포지토리가 호출됐는지 검증 — 다른 상태값이 전달되면 목록에 체결·취소 주문이 섞인다.
		verify(orderRepository).findByAccountIdAndStatusWithCursor(10L, OrderStatus.PENDING, null, null, 21);
	}

	@Test
	void getMyPendingOrdersReturnsEmptyContentWhenAccountHasNoPendingOrders() {
		Account account = account(Market.CRYPTO);
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(orderRepository.findByAccountIdAndStatusWithCursor(
			eq(10L), eq(OrderStatus.PENDING), isNull(), isNull(), eq(21)))
			.thenReturn(List.of());

		OrderListResponse response = orderService.getMyPendingOrders(
			USER_ID, Market.CRYPTO, null, 20);

		assertThat(response.content()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		assertThat(response.nextCursor()).isNull();
	}

	@Test
	void getMyPendingOrdersSetsNextCursorFromLimitthItemWhenFetchedCountExceedsLimit() {
		Account account = account(Market.CRYPTO);
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.CRYPTO))
			.thenReturn(account);

		Order order1 = pendingOrder(30L, NOW.minusMinutes(1));
		Order order2 = pendingOrder(20L, NOW.minusMinutes(2));
		Order order3 = pendingOrder(10L, NOW.minusMinutes(3));
		int limit = 2;
		when(orderRepository.findByAccountIdAndStatusWithCursor(
			eq(10L), eq(OrderStatus.PENDING), isNull(), isNull(), eq(limit + 1)))
			.thenReturn(List.of(order1, order2, order3));

		OrderListResponse response = orderService.getMyPendingOrders(
			USER_ID, Market.CRYPTO, null, limit);

		assertThat(response.hasNext()).isTrue();
		// 다음 페이지 있음(3건 조회) 시 nextCursor는 반환 페이지(limit=2건)의 마지막 항목인 order2 기준이어야 한다.
		assertThat(response.nextCursor()).isEqualTo(OrderCursor.encode(order2));
		assertThat(response.content()).hasSize(2);
	}

	@Test
	void getMyPendingOrdersDelegatesOwnershipAndMarketScopeValidationToAccountService() {
		Account account = account(Market.CRYPTO);
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.CRYPTO))
			.thenReturn(account);
		when(orderRepository.findByAccountIdAndStatusWithCursor(
			eq(10L), eq(OrderStatus.PENDING), isNull(), isNull(), eq(21)))
			.thenReturn(List.of());

		orderService.getMyPendingOrders(USER_ID, Market.CRYPTO, null, 20);

		verify(accountService).getAccountFor(USER_ID, Market.CRYPTO);
	}

	@Test
	void getMyPendingOrdersPropagatesExceptionThrownByCorruptedCursorWithoutQueryingRepository() {
		Account account = account(Market.CRYPTO);
		ReflectionTestUtils.setField(account, "id", 10L);
		when(accountService.getAccountFor(USER_ID, Market.CRYPTO))
			.thenReturn(account);

		assertThatThrownBy(() -> orderService.getMyPendingOrders(
			USER_ID, Market.CRYPTO, "garbage", 20))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));

		verify(orderRepository, never())
			.findByAccountIdAndStatusWithCursor(any(), any(), any(), any(), anyInt());
	}

	// 043 — getPracticeRunOrders(attempt 전용 조회)의 매핑·빈 결과 검증.
	@Test
	void getPracticeRunOrdersMapsRepositoryOrdersToOrderListItemResponseFields() {
		Instrument instrument = cryptoInstrument();
		ReflectionTestUtils.setField(instrument, "id", 42L);
		Order order = Order.createLimitPendingForPracticeAttempt(
			testUser(),
			account(Market.CRYPTO),
			instrument,
			OrderSide.BUY,
			new BigDecimal("1"),
			new BigDecimal("70000000"),
			5L,
			3L,
			IDEMPOTENCY_KEY,
			"h".repeat(64),
			NOW);
		ReflectionTestUtils.setField(order, "id", 100L);
		when(orderRepository.findPracticeRunOrders(5L, 3L)).thenReturn(List.of(order));

		List<OrderListItemResponse> result = orderService.getPracticeRunOrders(5L, 3L);

		assertThat(result).hasSize(1);
		OrderListItemResponse itemResponse = result.get(0);
		assertThat(itemResponse.orderId()).isEqualTo(100L);
		assertThat(itemResponse.market()).isEqualTo("CRYPTO");
		assertThat(itemResponse.instrumentId()).isEqualTo(42L);
		assertThat(itemResponse.side()).isEqualTo("BUY");
		assertThat(itemResponse.orderType()).isEqualTo("LIMIT");
		assertThat(itemResponse.status()).isEqualTo("PENDING");
		assertThat(itemResponse.quantity()).isEqualByComparingTo(new BigDecimal("1"));
		assertThat(itemResponse.requestedAt()).isEqualTo(NOW);
		assertThat(itemResponse.practiceAttemptId()).isEqualTo(5L);
		assertThat(itemResponse.practiceAttemptRunNumber()).isEqualTo(3L);
	}

	@Test
	void getPracticeRunOrdersReturnsEmptyListWhenRepositoryReturnsEmpty() {
		when(orderRepository.findPracticeRunOrders(5L, 3L)).thenReturn(List.of());

		List<OrderListItemResponse> result = orderService.getPracticeRunOrders(5L, 3L);

		assertThat(result).isEmpty();
	}

	private static Order pendingOrder(Long id, LocalDateTime requestedAt) {
		Instrument instrument = cryptoInstrument();
		ReflectionTestUtils.setField(instrument, "id", 42L);
		Order order = Order.createLimitPending(
			testUser(),
			account(Market.CRYPTO),
			instrument,
			OrderSide.BUY,
			new BigDecimal("1"),
			new BigDecimal("70000000"),
			"idem-key-pending-" + id,
			"h".repeat(64),
			requestedAt);
		ReflectionTestUtils.setField(order, "id", id);
		return order;
	}

	private static Instrument cryptoInstrument() {
		return Instrument.create(Market.CRYPTO, "BTC", "비트코인", new BigDecimal("70000000"), 5_000L, true, NOW);
	}

	private static Order order(Long id, LocalDateTime requestedAt) {
		Instrument instrument = stockInstrument();
		ReflectionTestUtils.setField(instrument, "id", 42L);
		Order order = Order.create(
			testUser(),
			account(Market.STOCK),
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			new BigDecimal("3"),
			"idem-key-" + id,
			"h".repeat(64),
			requestedAt);
		ReflectionTestUtils.setField(order, "id", id);
		return order;
	}

	private static Instrument stockInstrument() {
		return Instrument.create(Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, NOW);
	}

	private static Account account(com.finplay.api.domain.market.entity.Market market) {
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
