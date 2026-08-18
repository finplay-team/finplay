// 지정가 주문 1건을 취소(예약 해제)하는 서비스 — 잠금 순서 order → account → (SELL만) holding(plan.md)
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.TutorialAccount;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.account.service.TutorialAccountService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.PortfolioSellService;
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

	// plan.md "취소 흐름" — order(자기 자신) → account → (SELL만) holding 순으로 잠근다.
	// 검증 순서는 반드시 존재(404) → 소유(403) → 상태(409)여야 한다(spec.md LMT-003).
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
		// 생성·체결 시 예약과 동일 계산(spec.md) — 체결가가 항상 지정가로 고정되므로 예약액과 항상 정확히 일치한다.
		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(quantity, limitPrice);

		if (order.getSide() == OrderSide.SELL) {
			// SELL만 holding을 잠근다(잠금 순서 order → account → holding). BUY는 holding을 잠그지 않는다(plan.md).
			Holding holding = portfolioSellService.getHoldingForUpdate(account, order.getInstrument());
			holding.releaseReservedQuantity(quantity);
		} else if (order.getInstrument().isTutorialSample()) {
			// 샌드박스(튜토리얼) 종목의 지정가 매수 취소는 실제 Account 대신 튜토리얼 계좌의 예약을
			// 해제한다(047 TUTORIAL-CASH-ISOL-002 — 생성 시점에 이미 그 계좌에 예약이 걸려 있다).
			TutorialAccount tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
				account.getUser().getId(), account.getMarket(), LocalDateTime.now(clock));
			tutorialAccount.releaseReservedCash(reservation.total());
		} else {
			account.releaseReservedCash(reservation.total());
		}

		order.cancel();
	}
}
