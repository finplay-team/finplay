package com.finplay.api.domain.order.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderListItemResponse;
import com.finplay.api.domain.order.dto.response.OrderListResponse;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

	private static final String IDEMPOTENCY_KEY_CONSTRAINT_NAME = "uk_orders_user_idempotency";

	private final OrderExecutionService orderExecutionService;
	private final OrderRepository orderRepository;
	private final TradeRepository tradeRepository;
	private final AccountService accountService;

	public OrderResponse createOrder(Long userId, String idempotencyKey, OrderCreateRequest request) {
		String requestHash = calculateRequestHash(request);

		Optional<OrderResponse> replay = findReplayResponse(userId, idempotencyKey, requestHash);
		if (replay.isPresent()) {
			return replay.get();
		}

		try {
			return executeWithIdempotencyFallback(userId, idempotencyKey, requestHash, request);
		} catch (CannotAcquireLockException deadlock) {
			log.warn("주문 처리 중 데드락 발생 — 1회 재시도한다. userId={}, idempotencyKey={}",
				userId, idempotencyKey, deadlock);
		}
		try {
			return executeWithIdempotencyFallback(userId, idempotencyKey, requestHash, request);
		} catch (CannotAcquireLockException retryFailed) {
			log.error("주문 처리 중 재시도까지 데드락으로 실패했습니다. userId={}, idempotencyKey={}",
				userId, idempotencyKey, retryFailed);
			throw retryFailed;
		}
	}

	private OrderResponse executeWithIdempotencyFallback(
		Long userId, String idempotencyKey, String requestHash, OrderCreateRequest request) {
		try {
			return orderExecutionService.execute(userId, idempotencyKey, requestHash, request);
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			if (!isIdempotencyKeyConstraintViolation(concurrentDuplicate)) {
				throw concurrentDuplicate;
			}
			log.warn("주문 저장 중 멱등키 제약 위반 발생 — 경합으로 간주해 재조회를 시도한다. userId={}, idempotencyKey={}",
				userId, idempotencyKey, concurrentDuplicate);
			return findReplayResponse(userId, idempotencyKey, requestHash)
				.orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));
		}
	}

	private boolean isIdempotencyKeyConstraintViolation(DataIntegrityViolationException exception) {
		Throwable cause = exception.getMostSpecificCause();
		return cause.getMessage() != null && cause.getMessage().contains(IDEMPOTENCY_KEY_CONSTRAINT_NAME);
	}

	private Optional<OrderResponse> findReplayResponse(Long userId, String idempotencyKey, String requestHash) {
		return orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
			.map(existingOrder -> {
				if (!existingOrder.getRequestHash().equals(requestHash)) {
					throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
				}
				Trade existingTrade = tradeRepository.findByOrderId(existingOrder.getId())
					.orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));
				return OrderResponse.of(existingOrder, existingTrade);
			});
	}

	@Transactional(readOnly = true)
	public OrderListResponse getMyOrders(Long userId, Market market, String cursor, int limit) {
		Account account = accountService.getAccountFor(userId, market);
		OrderCursor parsedCursor = OrderCursor.parse(cursor);

		List<Order> fetched = orderRepository.findByAccountIdWithCursor(
			account.getId(),
			parsedCursor == null ? null : parsedCursor.requestedAt(),
			parsedCursor == null ? null : parsedCursor.id(),
			limit + 1);

		boolean hasNext = fetched.size() > limit;
		List<Order> page = hasNext ? fetched.subList(0, limit) : fetched;
		String nextCursor = hasNext ? OrderCursor.encode(page.get(page.size() - 1)) : null;

		List<OrderListItemResponse> content = page.stream().map(OrderListItemResponse::from).toList();
		return OrderListResponse.of(content, nextCursor, hasNext);
	}

	@Transactional(readOnly = true)
	public OrderListResponse getMyPendingOrders(Long userId, Market market, String cursor, int limit) {
		Account account = accountService.getAccountFor(userId, market);
		OrderCursor parsedCursor = OrderCursor.parse(cursor);

		List<Order> fetched = orderRepository.findByAccountIdAndStatusWithCursor(
			account.getId(),
			OrderStatus.PENDING,
			parsedCursor == null ? null : parsedCursor.requestedAt(),
			parsedCursor == null ? null : parsedCursor.id(),
			limit + 1);

		boolean hasNext = fetched.size() > limit;
		List<Order> page = hasNext ? fetched.subList(0, limit) : fetched;
		String nextCursor = hasNext ? OrderCursor.encode(page.get(page.size() - 1)) : null;

		List<OrderListItemResponse> content = page.stream().map(OrderListItemResponse::from).toList();
		return OrderListResponse.of(content, nextCursor, hasNext);
	}

	@Transactional(readOnly = true)
	public List<OrderListItemResponse> getPracticeRunOrders(Long attemptId, long runNumber) {
		return orderRepository.findPracticeRunOrders(attemptId, runNumber).stream()
			.map(OrderListItemResponse::from)
			.toList();
	}

	private String calculateRequestHash(OrderCreateRequest request) {
		String raw = "%s:%d:%s:%s:%s".formatted(
			request.market().name(),
			request.instrumentId(),
			request.side().name(),
			request.orderType(),
			request.quantity().toPlainString());
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashBytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
		}
	}
}
