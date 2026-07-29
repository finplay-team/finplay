// 주문 생성 요청을 받아 멱등성 판정 후 체결을 위임하고, 주문 목록 조회를 담당하는 오케스트레이터 서비스
package com.finplay.api.order.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

	private final OrderExecutionService orderExecutionService;
	private final OrderRepository orderRepository;
	private final TradeRepository tradeRepository;

	// 이슈 #22: 선제 조회로 재요청을 재현하고, 동시 경합은 유니크 제약 위반 캐치로 폴백한다(plan.md 확정 로직).
	public OrderResponse createOrder(Long userId, String idempotencyKey, OrderCreateRequest request) {
		String requestHash = calculateRequestHash(request);

		Optional<OrderResponse> replay = findReplayResponse(userId, idempotencyKey, requestHash);
		if (replay.isPresent()) {
			return replay.get();
		}

		try {
			return orderExecutionService.execute(userId, idempotencyKey, requestHash, request);
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			return findReplayResponse(userId, idempotencyKey, requestHash)
				.orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));
		}
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
	public List<OrderListItemResponse> getMyOrders(Long userId) {
		return orderRepository.findAllByUserIdOrderByRequestedAtDescIdDesc(userId).stream()
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
