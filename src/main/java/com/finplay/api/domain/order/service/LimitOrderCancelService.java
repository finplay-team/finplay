package com.finplay.api.domain.order.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
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
public class LimitOrderCancelService {

	private final OrderRepository orderRepository;
	private final AccountService accountService;
	private final TutorialAccountService tutorialAccountService;
	private final PortfolioSellService portfolioSellService;
	private final Clock clock;

	@Transactional
	public void cancelOrder(Long userId, Long orderId) {
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

		Account account = accountService.getAccountByIdForUpdate(order.getAccount().getId());

		BigDecimal quantity = order.getQuantity();
		BigDecimal limitPrice = order.getLimitPrice();
		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(quantity, limitPrice);

		if (order.getSide() == OrderSide.SELL) {
			Holding holding = portfolioSellService.getHoldingForUpdate(account, order.getInstrument());
			holding.releaseReservedQuantity(quantity);
		} else if (order.getInstrument().isTutorialSample()) {
			TutorialAccount tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
				account.getUser().getId(), account.getMarket(), LocalDateTime.now(clock));
			tutorialAccount.releaseReservedCash(reservation.total());
		} else {
			account.releaseReservedCash(reservation.total());
		}

		order.cancel();
	}
}
