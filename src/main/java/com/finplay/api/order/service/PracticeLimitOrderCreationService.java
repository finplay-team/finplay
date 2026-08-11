// 코인 튜토리얼 가상 가격 세션에 귀속된 교육 전용 지정가 BUY 주문의 검증·예약·PENDING 저장을 담당하는 서비스
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.dto.response.LimitOrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeLimitOrderCreationService {

	private final UserQueryService userQueryService;
	private final AccountService accountService;
	private final InstrumentService instrumentService;
	private final OrderRepository orderRepository;
	private final Clock clock;

	// education의 PracticeLimitOrderService가 세션을 owner 스코프로 잠근 뒤 같은 트랜잭션에서 호출한다
	// (plan.md 잠금 순서: session → account → order insert). side는 항상 BUY로 고정한다.
	// 멱등키는 클라이언트 헤더 없이 서버가 practice:{sessionId}:{UUID} 형태로 합성한다(plan.md 확정).
	@Transactional
	public LimitOrderResponse createSessionBuyOrder(
		Long userId, Long practicePriceSessionId, Long instrumentId, BigDecimal quantity, BigDecimal limitPrice) {
		Instrument instrument = getValidatedInstrument(instrumentId);
		LimitOrderCreationService.validateQuantityFormat(quantity);
		LimitOrderCreationService.validateLimitPrice(limitPrice);
		LimitOrderCreationService.validateMinOrderAmount(quantity, limitPrice, instrument);

		if (orderRepository.existsByPracticePriceSessionIdAndStatus(practicePriceSessionId, OrderStatus.PENDING)) {
			throw new BusinessException(ErrorCode.PRACTICE_LIMIT_ORDER_ALREADY_PENDING);
		}

		Account account = accountService.getAccountForUpdate(userId, com.finplay.api.account.domain.Market.CRYPTO);
		long cashRequired = LimitOrderFeeCalculator.calculate(quantity, limitPrice).total();
		if (account.getAvailableCash() < cashRequired) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
		}
		account.reserveCash(cashRequired);

		User user = userQueryService.getUser(userId);
		LocalDateTime now = LocalDateTime.now(clock);
		String idempotencyKey = "practice:%d:%s".formatted(practicePriceSessionId, UUID.randomUUID());
		String requestHash = calculateRequestHash(practicePriceSessionId, instrumentId, quantity, limitPrice);

		Order order = Order.createPracticeLimitPendingBuy(
			user, account, instrument, quantity, limitPrice, practicePriceSessionId, idempotencyKey, requestHash, now);
		orderRepository.save(order);
		return LimitOrderResponse.from(order);
	}

	private Instrument getValidatedInstrument(Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		if (instrument.getMarket() != Market.CRYPTO || !instrument.isTradable()) {
			throw new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE);
		}
		return instrument;
	}

	// LimitOrderService.calculateRequestHash와 같은 알고리즘 — 이 서비스는 Idempotency-Key 헤더가 없어
	// 재요청 재현에 쓰지 않지만(플랜 확정), Order.requestHash 컬럼은 NOT NULL이라 결정적으로 채운다.
	private String calculateRequestHash(
		Long practicePriceSessionId, Long instrumentId, BigDecimal quantity, BigDecimal limitPrice) {
		String raw = "practice:%d:%d:%s:%s".formatted(
			practicePriceSessionId, instrumentId, quantity.toPlainString(), limitPrice.toPlainString());
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashBytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
		}
	}
}
