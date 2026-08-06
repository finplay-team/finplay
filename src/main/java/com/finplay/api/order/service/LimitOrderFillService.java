// 지정가 주문 1건을 목표가로 체결(예약 확정)하는 서비스 — 잠금 순서 order → account → holding(plan.md)
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.PortfolioBuyService;
import com.finplay.api.portfolio.service.PortfolioSellService;
import com.finplay.api.portfolio.service.SellAllocationDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LimitOrderFillService {

	private final OrderRepository orderRepository;
	private final TradeRepository tradeRepository;
	private final AccountService accountService;
	private final PortfolioBuyService portfolioBuyService;
	private final PortfolioSellService portfolioSellService;
	private final Clock clock;
	private final ApplicationEventPublisher eventPublisher;

	// plan.md "체결 리스너·체결 서비스" — order(자기 자신) → account → (SELL만) holding 순으로 잠근다.
	// 이미 order를 락으로 잡은 뒤 PENDING 여부를 재확인하므로 같은 주문에 이벤트가 중복 도착해도 두 번째 호출은 no-op.
	@Transactional
	public void fillIfPending(Long orderId) {
		Order order = orderRepository.findByIdForUpdate(orderId)
			.orElseThrow(() -> new IllegalStateException("체결 대상 주문을 찾을 수 없습니다. orderId=" + orderId));
		if (order.getStatus() != OrderStatus.PENDING) {
			return;
		}

		Account account = accountService.getAccountByIdForUpdate(order.getAccount().getId());

		BigDecimal quantity = order.getQuantity();
		BigDecimal limitPrice = order.getLimitPrice();
		// 생성 시 예약과 동일 계산(spec.md) — 체결가가 항상 지정가로 고정되므로 예약액과 항상 정확히 일치한다.
		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(quantity, limitPrice);
		long amount = reservation.amount();
		long fee = reservation.fee();
		LocalDateTime now = LocalDateTime.now(clock);

		if (order.getSide() == OrderSide.SELL) {
			fillSell(order, account, quantity, limitPrice, amount, fee, now);
		} else {
			fillBuy(order, account, quantity, limitPrice, amount, fee, now);
		}
	}

	private void fillBuy(
		Order order, Account account, BigDecimal quantity, BigDecimal limitPrice, long amount, long fee,
		LocalDateTime now) {
		Instrument instrument = order.getInstrument();

		account.confirmReservedCash(amount + fee);

		Trade trade = Trade.of(
			order, account, instrument, null, order.getSide(), limitPrice, quantity, amount, fee, null, now, now);
		tradeRepository.save(trade);

		// 기존 시장가 매수와 동일한 lot 생성 로직 재사용 — holding이 없으면(신규 종목 첫 매수) 여기서 새로 만든다.
		portfolioBuyService.applyBuyTrade(account, instrument, trade, quantity, limitPrice, fee, now);

		order.markFilled();
	}

	private void fillSell(
		Order order, Account account, BigDecimal quantity, BigDecimal limitPrice, long amount, long fee,
		LocalDateTime now) {
		Instrument instrument = order.getInstrument();

		// SELL은 여기서 직접 holding을 잠근다(잠금 순서 account → holding). BUY도 이제 holding을 잠근다 —
		// applyBuyTrade(PortfolioBuyService)가 내부에서 findByAccountIdAndInstrumentIdForUpdate로 잠근다(이슈 #224).
		Holding holding = portfolioSellService.getHoldingForUpdate(account, instrument);
		holding.releaseReservedQuantity(quantity);

		Trade trade = Trade.of(
			order, account, instrument, null, order.getSide(), limitPrice, quantity, amount, fee, null, now, now);
		tradeRepository.save(trade);

		// 기존 FIFO lot 소비 재사용 — releaseReservedQuantity(예약 해제)와 applySell(실보유 차감)을 함께 호출한다.
		SellAllocationDto allocation = portfolioSellService.applySellTrade(holding, trade, quantity, now);

		// 기존 시장가 매도(OrderExecutionService)와 동일한 실현손익 공식·반영을 공유 메서드로 재사용한다.
		portfolioSellService.finalizeSellRealizedPnl(account, trade, amount, fee, allocation);

		order.markFilled();
		// 커밋 이후(after-commit)에만 랭킹에 반영되도록 이벤트만 발행한다 — 기존 시장가 매도와 동일 훅 재사용.
		eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));
	}
}
