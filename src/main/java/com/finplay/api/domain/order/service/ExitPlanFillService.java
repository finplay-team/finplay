package com.finplay.api.domain.order.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanCondition;
import com.finplay.api.domain.order.entity.ExitPlanConditionStatus;
import com.finplay.api.domain.order.entity.ExitPlanConditionType;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.ExitPlanConditionRepository;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.domain.portfolio.service.SellAllocationDto;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ExitPlanFillService {

	private final ExitPlanRepository exitPlanRepository;
	private final ExitPlanConditionRepository exitPlanConditionRepository;
	private final AccountService accountService;
	private final PortfolioSellService portfolioSellService;
	private final OrderRepository orderRepository;
	private final TradeRepository tradeRepository;
	private final Clock clock;
	private final ApplicationEventPublisher eventPublisher;
	private final EntityManager entityManager;

	@Transactional
	public void fillIfPending(Long exitPlanId, BigDecimal currentPrice) {
		ExitPlan ownershipCheck = exitPlanRepository.findById(exitPlanId).orElse(null);
		if (ownershipCheck == null) {
			return;
		}

		Holding preloadedHolding = ownershipCheck.getHolding();
		Long accountId = preloadedHolding.getAccount().getId();
		Instrument instrumentRef = ownershipCheck.getInstrument();
		entityManager.flush();
		entityManager.detach(ownershipCheck);
		entityManager.detach(preloadedHolding);

		Account account = accountService.getAccountByIdForUpdate(accountId);
		Holding holding = portfolioSellService.getHoldingForUpdate(account, instrumentRef);

		ExitPlan plan = exitPlanRepository.findByIdForUpdate(exitPlanId).orElse(null);
		if (plan == null || !plan.isPending()) {
			return;
		}

		ExitPlanConditionType triggeredType = resolveTriggeredCondition(plan, currentPrice);
		if (triggeredType == null) {
			return;
		}

		LocalDateTime now = LocalDateTime.now(clock);
		Order order = executeMarketSell(plan, account, holding, currentPrice, now);

		if (triggeredType == ExitPlanConditionType.TAKE_PROFIT) {
			plan.fillTakeProfit(order, now);
		} else {
			plan.fillStopLoss(order, now);
		}
		closeConditions(plan.getId(), triggeredType);
	}

	private ExitPlanConditionType resolveTriggeredCondition(ExitPlan plan, BigDecimal currentPrice) {
		if (currentPrice.compareTo(plan.getTakeProfitPrice()) >= 0) {
			return ExitPlanConditionType.TAKE_PROFIT;
		}
		if (currentPrice.compareTo(plan.getStopLossPrice()) <= 0) {
			return ExitPlanConditionType.STOP_LOSS;
		}
		return null;
	}

	private Order executeMarketSell(
		ExitPlan plan, Account account, Holding holding, BigDecimal currentPrice, LocalDateTime now) {
		Instrument instrument = plan.getInstrument();
		BigDecimal quantity = plan.getQuantity();

		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(quantity, currentPrice);
		long amount = reservation.amount();
		long fee = reservation.fee();

		String idempotencyKey = triggerIdempotencyKey(plan.getId());
		Order order = plan.getPracticeAttemptId() == null
			? Order.create(
				plan.getUser(), account, instrument, OrderSide.SELL, OrderType.MARKET, quantity, idempotencyKey,
				sha256Hex(idempotencyKey), now)
			: Order.createForPracticeAttempt(
				plan.getUser(), account, instrument, OrderSide.SELL, OrderType.MARKET, quantity,
				plan.getPracticeAttemptId(), plan.getPracticeAttemptRunNumber(), idempotencyKey,
				sha256Hex(idempotencyKey), now);
		orderRepository.save(order);

		Trade trade = Trade.of(
			order, account, instrument, null, OrderSide.SELL, currentPrice, quantity, amount, fee, null, now, now);
		tradeRepository.save(trade);

		holding.releaseReservedQuantity(quantity);
		SellAllocationDto allocation = portfolioSellService.applySellTrade(holding, trade, quantity, now);
		portfolioSellService.finalizeSellRealizedPnl(account, trade, amount, fee, allocation, now);

		if (!instrument.isTutorialSample()) {
			eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));
		}
		return order;
	}

	private void closeConditions(Long planId, ExitPlanConditionType triggeredType) {
		List<ExitPlanCondition> conditions = exitPlanConditionRepository.findByExitPlanIdOrderByIdAsc(planId);
		for (ExitPlanCondition condition : conditions) {
			if (condition.getStatus() != ExitPlanConditionStatus.PENDING) {
				continue;
			}
			if (condition.getConditionType() == triggeredType) {
				condition.trigger();
			} else {
				condition.cancelByOco();
			}
		}
	}

	private String triggerIdempotencyKey(Long exitPlanId) {
		return "EXIT_PLAN_TRIGGER-" + exitPlanId;
	}

	private String sha256Hex(String raw) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashBytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
		}
	}
}
