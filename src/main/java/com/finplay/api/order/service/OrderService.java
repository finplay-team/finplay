// 주문 생성 요청을 받아 멱등성 판정 후 체결을 위임하고, 주문 목록 조회를 담당하는 오케스트레이터 서비스
package com.finplay.api.order.service;

import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

	private final OrderExecutionService orderExecutionService;
	private final OrderRepository orderRepository;

	// TODO(이슈 #22 항목 3): 멱등성 재요청 응답 재현·충돌 판정을 여기에 구현한다. 지금은 순수 위임만 한다(회귀 없음).
	public OrderResponse createOrder(Long userId, String idempotencyKey, OrderCreateRequest request) {
		String requestHash = calculateRequestHash(request);
		return orderExecutionService.execute(userId, idempotencyKey, requestHash, request);
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
