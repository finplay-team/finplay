// 주문 생성 요청을 받아 멱등성 판정 후 체결을 위임하고, 주문 목록 조회를 담당하는 오케스트레이터 서비스
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

	// PR #93 리뷰 권장사항: execute() 트랜잭션 안에서는 이 제약 말고도 uk_holdings_account_instrument 등
	// 다른 유니크 제약이 위반될 수 있다. 그런 경우까지 멱등키 경합으로 잘못 판단해 409로 감추지 않도록,
	// 실제 위반된 제약이 이것일 때만 재조회 폴백을 탄다.
	private static final String IDEMPOTENCY_KEY_CONSTRAINT_NAME = "uk_orders_user_idempotency";

	private final OrderExecutionService orderExecutionService;
	private final OrderRepository orderRepository;
	private final TradeRepository tradeRepository;
	private final AccountService accountService;

	// 이슈 #22: 선제 조회로 재요청을 재현하고, 동시 경합은 유니크 제약 위반 캐치로 폴백한다(plan.md 확정 로직).
	public OrderResponse createOrder(Long userId, String idempotencyKey, OrderCreateRequest request) {
		String requestHash = calculateRequestHash(request);

		Optional<OrderResponse> replay = findReplayResponse(userId, idempotencyKey, requestHash);
		if (replay.isPresent()) {
			return replay.get();
		}

		// ADR-0028 — holdings 신규 생성 INSERT가 서로 다른 계좌·종목 간에도 MySQL 갭 락 데드락을 일으킬
		// 수 있다(트랜잭션 전체 롤백이라 부분 커밋 없음, 같은 인자로 재시도해도 이중 체결 위험 없음).
		// 격리수준(READ COMMITTED) 조정으로 빈도를 낮췄지만 완전히 없애지는 못해 1회만 재시도하고,
		// 또 실패하면 원인을 감추지 않고 그대로 전파한다. 재시도 호출도 같은 멱등키 충돌 폴백을 타야
		// 하므로(PR #514 리뷰 권장사항) executeWithIdempotencyFallback을 그대로 재사용한다.
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
				// 멱등키 경합이 아닌 다른 유니크 제약 위반(예: holdings 동시성)이다 — 원인을 감추지 않고 그대로 전파한다.
				// 방치된 방어적 경로: 원인 조사는 2차 동시성 고도화(분산락 등)에서 다룬다.
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

	// 기존 Order를 찾으면 본문 해시를 비교해 응답을 재구성하거나(일치) 즉시 409(불일치)를 던진다.
	// 찾지 못하면 빈 Optional — 호출부가 신규 생성 경로로 진행한다.
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

	// LMT-004(이슈 #235): getMyOrders와 동일 구조에 status=PENDING 필터만 얹는다.
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

	// 043 — 튜토리얼 education 도메인이 호출하는 attempt 전용 조회. attemptId·runNumber만 받고 education의
	// 어떤 타입도 알지 않는다(TradeService.summarizePracticeRun과 동일 경계).
	@Transactional(readOnly = true)
	public List<OrderListItemResponse> getPracticeRunOrders(Long attemptId, long runNumber) {
		return orderRepository.findPracticeRunOrders(attemptId, runNumber).stream()
			.map(OrderListItemResponse::from)
			.toList();
	}

	// 설계 노트 8: market:instrumentId:side:orderType:quantity 형식 문자열을 SHA-256 hex로 해시한다.
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
