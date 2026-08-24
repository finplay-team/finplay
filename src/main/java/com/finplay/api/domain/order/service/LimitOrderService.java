// 지정가 주문 생성 요청을 받아 멱등성 판정 후 검증·예약·저장 실행을 위임하는 오케스트레이터 서비스
package com.finplay.api.domain.order.service;

import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LimitOrderService {

	// OrderService와 동일한 판단 근거(PR #93 리뷰 권장사항) — 이 제약 위반일 때만 멱등키 경합으로 간주한다.
	private static final String IDEMPOTENCY_KEY_CONSTRAINT_NAME = "uk_orders_user_idempotency";

	private final LimitOrderCreationService limitOrderCreationService;
	private final OrderRepository orderRepository;

	// OrderService.createOrder와 동일한 패턴(이슈 #22): 선제 조회로 재요청을 재현하고, 동시 경합은
	// 유니크 제약 위반 캐치로 폴백한다. 지정가는 생성 시점에 Trade가 없으므로 Order만으로 응답을 재구성한다.
	public LimitOrderResponse createLimitOrder(Long userId, String idempotencyKey, LimitOrderCreateRequest request) {
		String requestHash = calculateRequestHash(request);

		Optional<LimitOrderResponse> replay = findReplayResponse(userId, idempotencyKey, requestHash);
		if (replay.isPresent()) {
			return replay.get();
		}

		try {
			return limitOrderCreationService.execute(userId, idempotencyKey, requestHash, request);
		} catch (DataIntegrityViolationException concurrentDuplicate) {
			if (!isIdempotencyKeyConstraintViolation(concurrentDuplicate)) {
				throw concurrentDuplicate;
			}
			log.warn(
				"지정가 주문 저장 중 멱등키 제약 위반 발생 — 경합으로 간주해 재조회를 시도한다. userId={}, idempotencyKey={}",
				userId, idempotencyKey, concurrentDuplicate);
			return findReplayResponse(userId, idempotencyKey, requestHash)
				.orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));
		}
	}

	private boolean isIdempotencyKeyConstraintViolation(DataIntegrityViolationException exception) {
		Throwable cause = exception.getMostSpecificCause();
		return cause.getMessage() != null && cause.getMessage().contains(IDEMPOTENCY_KEY_CONSTRAINT_NAME);
	}

	private Optional<LimitOrderResponse> findReplayResponse(Long userId, String idempotencyKey, String requestHash) {
		return orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
			.map(existingOrder -> {
				if (!existingOrder.getRequestHash().equals(requestHash)) {
					throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
				}
				return LimitOrderResponse.from(existingOrder);
			});
	}

	// OrderService.calculateRequestHash와 같은 형식·알고리즘 — orderType 대신 limitPrice를 싣는다(이 엔드포인트는 항상 LIMIT).
	private String calculateRequestHash(LimitOrderCreateRequest request) {
		String raw = "%s:%d:%s:%s:%s".formatted(
			request.market().name(),
			request.instrumentId(),
			request.side().name(),
			request.quantity().toPlainString(),
			request.limitPrice().toPlainString());
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashBytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
		}
	}
}
