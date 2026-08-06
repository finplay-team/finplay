// 지정가 주문 1건을 수정(해제 후 재예약)하는 서비스 — 잠금 순서 order → account → (SELL만) holding(plan.md)
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.dto.request.LimitOrderUpdateRequest;
import com.finplay.api.order.dto.response.LimitOrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.PortfolioSellService;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LimitOrderModifyService {

	private final OrderRepository orderRepository;
	private final AccountService accountService;
	private final PortfolioSellService portfolioSellService;

	// plan.md "수정 흐름" — 0.요청 형식(400) → 1.order 락+존재(404) → 2.소유(403) → 3.상태(409)
	// → 4.최종값 합성 → 5.형식·최소주문금액 재검증 → 6.account 락
	// → 7~9.BUY/SELL 해제→재예약(재예약 실패 시 트랜잭션 롤백으로 해제도 취소됨) → 10.order.modify() → 11.응답.
	@Transactional
	public LimitOrderResponse modifyOrder(Long userId, Long orderId, LimitOrderUpdateRequest request) {
		if (request.limitPrice() == null && request.quantity() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "변경할 값이 없습니다.");
		}

		Order order = orderRepository.findByIdForUpdate(orderId)
			.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

		if (!order.getUser().getId().equals(userId)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}

		if (order.getStatus() == OrderStatus.FILLED) {
			throw new BusinessException(ErrorCode.ORDER_ALREADY_FILLED);
		}
		if (order.getStatus() == OrderStatus.CANCELLED) {
			throw new BusinessException(ErrorCode.ORDER_ALREADY_CANCELLED);
		}

		BigDecimal finalQuantity = request.quantity() != null ? request.quantity() : order.getQuantity();
		BigDecimal finalLimitPrice = request.limitPrice() != null ? request.limitPrice() : order.getLimitPrice();

		validateQuantityFormat(finalQuantity);
		validateLimitPrice(finalLimitPrice);
		validateMinOrderAmount(finalQuantity, finalLimitPrice, order.getInstrument());

		Account account = accountService.getAccountByIdForUpdate(order.getAccount().getId());

		if (order.getSide() == OrderSide.SELL) {
			// SELL만 holding을 잠근다(잠금 순서 order → account → holding). BUY는 holding을 잠그지 않는다(plan.md).
			Holding holding = portfolioSellService.getHoldingForUpdate(account, order.getInstrument());
			holding.releaseReservedQuantity(order.getQuantity());
			if (holding.getAvailableQuantity().compareTo(finalQuantity) < 0) {
				throw new BusinessException(ErrorCode.INSUFFICIENT_QTY);
			}
			holding.reserveQuantity(finalQuantity);
		} else {
			LimitOrderFeeCalculator.Reservation oldReservation = LimitOrderFeeCalculator.calculate(
				order.getQuantity(), order.getLimitPrice());
			LimitOrderFeeCalculator.Reservation newReservation = LimitOrderFeeCalculator.calculate(
				finalQuantity, finalLimitPrice);
			account.releaseReservedCash(oldReservation.total());
			if (account.getAvailableCash() < newReservation.total()) {
				throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
			}
			account.reserveCash(newReservation.total());
		}

		order.modify(finalQuantity, finalLimitPrice);
		return LimitOrderResponse.from(order);
	}

	// LMT-001 생성 시 검증과 동일 규칙(LimitOrderCreationService) — 최종 합성값 기준으로 재적용한다.
	private void validateQuantityFormat(BigDecimal quantity) {
		if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "수량은 0보다 커야 합니다.");
		}
		if (quantity.stripTrailingZeros().scale() > 8) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 수량은 소수점 8자리 이하여야 합니다.");
		}
	}

	private void validateLimitPrice(BigDecimal limitPrice) {
		if (limitPrice.compareTo(BigDecimal.ZERO) <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "지정가는 0보다 커야 합니다.");
		}
	}

	private void validateMinOrderAmount(BigDecimal quantity, BigDecimal limitPrice, Instrument instrument) {
		BigDecimal rawAmount = quantity.multiply(limitPrice);
		if (rawAmount.compareTo(BigDecimal.valueOf(instrument.getMinOrderAmount())) < 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 최소 주문금액에 미달합니다.");
		}
	}
}
