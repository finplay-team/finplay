package com.finplay.api.domain.order.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.order.dto.request.LimitOrderUpdateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LimitOrderModifyService {

	private final OrderRepository orderRepository;
	private final AccountService accountService;
	private final TutorialAccountService tutorialAccountService;
	private final PortfolioSellService portfolioSellService;
	private final Clock clock;

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
			if (order.getInstrument().isTutorialSample()) {
				TutorialAccount tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
					account.getUser().getId(), account.getMarket(), LocalDateTime.now(clock));
				tutorialAccount.releaseReservedCash(oldReservation.total());
				if (tutorialAccount.getAvailableCash() < newReservation.total()) {
					throw new BusinessException(ErrorCode.TUTORIAL_INSUFFICIENT_CASH);
				}
				tutorialAccount.reserveCash(newReservation.total());
			} else {
				account.releaseReservedCash(oldReservation.total());
				if (account.getAvailableCash() < newReservation.total()) {
					throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
				}
				account.reserveCash(newReservation.total());
			}
		}

		order.modify(finalQuantity, finalLimitPrice);
		return LimitOrderResponse.from(order);
	}

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
