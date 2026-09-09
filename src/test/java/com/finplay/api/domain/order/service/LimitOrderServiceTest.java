package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class LimitOrderServiceTest {

	private static final Long USER_ID = 1L;
	private static final String IDEMPOTENCY_KEY = "idem-limit-1";
	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final LimitOrderCreationService limitOrderCreationService = mock(LimitOrderCreationService.class);
	private final OrderRepository orderRepository = mock(OrderRepository.class);

	private final LimitOrderService limitOrderService = new LimitOrderService(
		limitOrderCreationService, orderRepository);

	@Test
	void createLimitOrderReturnsReconstructedResponseWhenSameKeyAndSameBodyIsReplayed() {
		Order existingOrder = pendingOrder(requestHashOf(sampleRequest()));
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.of(existingOrder));

		LimitOrderResponse response = limitOrderService.createLimitOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest());

		assertThat(response).isEqualTo(LimitOrderResponse.from(existingOrder));
		verifyNoInteractions(limitOrderCreationService);
	}

	@Test
	void createLimitOrderThrowsIdempotencyConflictWhenSameKeyButDifferentBodyIsReplayed() {
		Order existingOrder = pendingOrder("different-hash");
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.of(existingOrder));

		assertThatThrownBy(() -> limitOrderService.createLimitOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest()))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
		verifyNoInteractions(limitOrderCreationService);
	}

	@Test
	void createLimitOrderExecutesAndReturnsResultWhenIdempotencyKeyIsNew() {
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
		LimitOrderResponse executionResult = new LimitOrderResponse(
			200L, "CRYPTO", 1L, "BUY", "LIMIT", "PENDING", new BigDecimal("1"), new BigDecimal("70000000"), NOW);
		when(limitOrderCreationService.execute(any(), anyString(), anyString(), any())).thenReturn(executionResult);

		LimitOrderResponse response = limitOrderService.createLimitOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest());

		assertThat(response).isEqualTo(executionResult);
		verify(limitOrderCreationService).execute(any(), anyString(), anyString(), any());
	}

	@Test
	void createLimitOrderReturnsReconstructedResponseWhenExecuteHitsConcurrentUniqueConstraintButReplayIsFound() {
		Order existingOrder = pendingOrder(requestHashOf(sampleRequest()));
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
			.thenReturn(Optional.empty(), Optional.of(existingOrder));
		when(limitOrderCreationService.execute(any(), anyString(), anyString(), any()))
			.thenThrow(new DataIntegrityViolationException(
				"Duplicate entry '1-idem-limit-1' for key 'orders.uk_orders_user_idempotency'"));

		LimitOrderResponse response = limitOrderService.createLimitOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest());

		assertThat(response).isEqualTo(LimitOrderResponse.from(existingOrder));
		verify(limitOrderCreationService).execute(any(), anyString(), anyString(), any());
	}

	@Test
	void createLimitOrderThrowsIdempotencyConflictWhenExecuteHitsConcurrentUniqueConstraintAndReplayIsNotFound() {
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
		when(limitOrderCreationService.execute(any(), anyString(), anyString(), any()))
			.thenThrow(new DataIntegrityViolationException(
				"Duplicate entry '1-idem-limit-1' for key 'orders.uk_orders_user_idempotency'"));

		assertThatThrownBy(() -> limitOrderService.createLimitOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest()))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
	}

	@Test
	void createLimitOrderRethrowsUnrelatedUniqueConstraintViolationWithoutMaskingItAsIdempotencyConflict() {
		when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
		DataIntegrityViolationException holdingsConstraintViolation = new DataIntegrityViolationException(
			"Duplicate entry '10-1' for key 'holdings.uk_holdings_account_instrument'");
		when(limitOrderCreationService.execute(any(), anyString(), anyString(), any()))
			.thenThrow(holdingsConstraintViolation);

		assertThatThrownBy(() -> limitOrderService.createLimitOrder(USER_ID, IDEMPOTENCY_KEY, sampleRequest()))
			.isSameAs(holdingsConstraintViolation);

		verify(orderRepository, times(1)).findByUserIdAndIdempotencyKey(USER_ID, IDEMPOTENCY_KEY);
	}

	private static Order pendingOrder(String requestHash) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 1L);
		Order order = Order.createLimitPending(
			testUser(),
			account(),
			instrument,
			OrderSide.BUY,
			new BigDecimal("1"),
			new BigDecimal("70000000"),
			IDEMPOTENCY_KEY,
			requestHash,
			NOW);
		ReflectionTestUtils.setField(order, "id", 100L);
		return order;
	}

	private static Account account() {
		return Account.create(testUser(), Market.CRYPTO, NOW);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}

	private static LimitOrderCreateRequest sampleRequest() {
		return new LimitOrderCreateRequest(Market.CRYPTO, 1L, OrderSide.BUY, new BigDecimal("1"),
			new BigDecimal("70000000"));
	}

	private static String requestHashOf(LimitOrderCreateRequest request) {
		String raw = "%s:%d:%s:%s:%s".formatted(
			request.market().name(),
			request.instrumentId(),
			request.side().name(),
			request.quantity().toPlainString(),
			request.limitPrice().toPlainString());
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(hashBytes);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
		}
	}
}
