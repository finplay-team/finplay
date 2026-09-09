package com.finplay.api.domain.order.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioBuyService;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.domain.portfolio.service.SellAllocationDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LimitOrderFillService {

	private final OrderRepository orderRepository;
	private final TradeRepository tradeRepository;
	private final AccountService accountService;
	private final TutorialAccountService tutorialAccountService;
	private final PortfolioBuyService portfolioBuyService;
	private final PortfolioSellService portfolioSellService;
	private final PracticeOrderAttributionPort practiceOrderAttributionPort;
	private final Clock clock;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void fillIfPending(Long orderId) {
		fillOnePending(orderId, LocalDateTime.now(clock));
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void fillIfPending(Long orderId, LocalDateTime pricedAt) {
		fillOnePending(orderId, pricedAt);
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void fillBatch(List<Long> orderIds) {
		LocalDateTime pricedAt = LocalDateTime.now(clock);

		List<Long> sortedOrderIds = orderIds.stream().sorted().toList();
		Map<Long, Order> ordersById = orderRepository.findByIdInForUpdate(sortedOrderIds).stream()
			.collect(Collectors.toMap(Order::getId, Function.identity()));

		List<Order> pendingOrders = orderIds.stream()
			.map(ordersById::get)
			.filter(order -> order != null && order.getStatus() == OrderStatus.PENDING)
			.toList();

		Map<Long, Account> accountsById;
		Map<Long, Holding> holdingsByAccountId;
		if (pendingOrders.isEmpty()) {
			accountsById = Map.of();
			holdingsByAccountId = new HashMap<>();
		} else {
			List<Long> accountIds = pendingOrders.stream()
				.map(order -> order.getAccount().getId())
				.distinct()
				.sorted()
				.toList();
			accountsById = accountService.getAccountsByIdsForUpdate(accountIds).stream()
				.collect(Collectors.toMap(Account::getId, Function.identity()));

			Long instrumentId = pendingOrders.get(0).getInstrument().getId();
			holdingsByAccountId = new HashMap<>(
				portfolioBuyService.findExistingHoldingsForChunkUpdate(accountIds, instrumentId)
					.stream()
					.collect(Collectors.toMap(holding -> holding.getAccount().getId(), Function.identity())));
		}

		for (Long orderId : orderIds) {
			Order order = ordersById.get(orderId);
			if (order == null) {
				throw new IllegalStateException("체결 대상 주문을 찾을 수 없습니다. orderId=" + orderId);
			}
			if (order.getStatus() != OrderStatus.PENDING) {
				continue;
			}
			Account account = accountsById.get(order.getAccount().getId());
			if (account == null) {
				throw new IllegalStateException("체결 대상 계좌를 찾을 수 없습니다. accountId=" + order.getAccount().getId());
			}
			fillOnePendingWithLockedResources(order, account, holdingsByAccountId, pricedAt);
		}
	}

	private void fillOnePending(Long orderId, LocalDateTime pricedAt) {
		Optional<PracticeOrderFillContextDto> practiceContext = orderRepository.findPracticeFillAttribution(orderId)
			.map(attribution -> practiceOrderAttributionPort.lockForFill(attribution, pricedAt));
		Order order = orderRepository.findByIdForUpdate(orderId)
			.orElseThrow(() -> new IllegalStateException("체결 대상 주문을 찾을 수 없습니다. orderId=" + orderId));
		if (order.getStatus() != OrderStatus.PENDING) {
			return;
		}
		if (practiceContext.isPresent() && !practiceContext.get().currentRun()) {
			throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
		}
		BigDecimal limitPrice = order.getLimitPrice();
		BigDecimal executionPrice = practiceContext
			.map(PracticeOrderFillContextDto::canonicalPrice)
			.orElse(limitPrice);
		if (practiceContext.isPresent() && !isTriggered(order, executionPrice)) {
			return;
		}

		Account account = accountService.getAccountByIdForUpdate(order.getAccount().getId());

		BigDecimal quantity = order.getQuantity();
		LimitOrderFeeCalculator.Reservation reserved = LimitOrderFeeCalculator.calculate(quantity, limitPrice);
		LimitOrderFeeCalculator.Reservation execution = LimitOrderFeeCalculator.calculate(quantity, executionPrice);
		long amount = execution.amount();
		long fee = execution.fee();

		if (order.getSide() == OrderSide.SELL) {
			fillSell(order, account, quantity, executionPrice, amount, fee, pricedAt);
		} else {
			fillBuy(
				order, account, quantity, executionPrice, amount, fee, reserved.total(), practiceContext.isPresent(),
				pricedAt);
		}
	}

	private void fillBuy(
		Order order, Account account, BigDecimal quantity, BigDecimal executionPrice, long amount, long fee,
		long reservedCash, boolean canonicalPracticeFill, LocalDateTime now) {
		Instrument instrument = order.getInstrument();

		if (instrument.isTutorialSample()) {
			TutorialAccount tutorialAccount = tutorialAccountService
				.getOrCreateForUpdate(account.getUser().getId(), account.getMarket(), now);
			if (canonicalPracticeFill) {
				tutorialAccount.releaseReservedCash(reservedCash);
				tutorialAccount.deductCash(amount + fee);
			} else {
				tutorialAccount.confirmReservedCash(amount + fee);
			}
		} else {
			if (canonicalPracticeFill) {
				account.releaseReservedCash(reservedCash);
				account.deductCash(amount + fee);
			} else {
				account.confirmReservedCash(amount + fee);
			}
		}

		Trade trade = Trade.of(
			order, account, instrument, null, order.getSide(), executionPrice, quantity, amount, fee, null, now, now);
		tradeRepository.save(trade);

		portfolioBuyService.applyBuyTrade(account, instrument, trade, quantity, executionPrice, fee, now);

		order.markFilled();
		practiceOrderAttributionPort.createRiskSnapshotOnBuyFill(order, trade, now);
	}

	private void fillSell(
		Order order, Account account, BigDecimal quantity, BigDecimal executionPrice, long amount, long fee,
		LocalDateTime now) {
		Instrument instrument = order.getInstrument();

		Holding holding = portfolioSellService.getHoldingForUpdate(account, instrument);
		holding.releaseReservedQuantity(quantity);

		Trade trade = Trade.of(
			order, account, instrument, null, order.getSide(), executionPrice, quantity, amount, fee, null, now, now);
		tradeRepository.save(trade);

		SellAllocationDto allocation = portfolioSellService.applySellTrade(holding, trade, quantity, now);

		portfolioSellService.finalizeSellRealizedPnl(account, trade, amount, fee, allocation, now);

		order.markFilled();
		if (!instrument.isTutorialSample()) {
			eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));
		}
	}

	private void fillOnePendingWithLockedResources(
		Order order, Account account, Map<Long, Holding> holdingsByAccountId, LocalDateTime pricedAt) {
		Optional<PracticeOrderFillContextDto> practiceContext = orderRepository
			.findPracticeFillAttribution(order.getId())
			.map(attribution -> practiceOrderAttributionPort.lockForFill(attribution, pricedAt));
		if (practiceContext.isPresent() && !practiceContext.get().currentRun()) {
			throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
		}
		BigDecimal limitPrice = order.getLimitPrice();
		BigDecimal executionPrice = practiceContext
			.map(PracticeOrderFillContextDto::canonicalPrice)
			.orElse(limitPrice);
		if (practiceContext.isPresent() && !isTriggered(order, executionPrice)) {
			return;
		}

		BigDecimal quantity = order.getQuantity();
		LimitOrderFeeCalculator.Reservation reserved = LimitOrderFeeCalculator.calculate(quantity, limitPrice);
		LimitOrderFeeCalculator.Reservation execution = LimitOrderFeeCalculator.calculate(quantity, executionPrice);
		long amount = execution.amount();
		long fee = execution.fee();

		if (order.getSide() == OrderSide.SELL) {
			fillSellWithLockedHolding(
				order, account, holdingsByAccountId, quantity, executionPrice, amount, fee, pricedAt);
		} else {
			fillBuyWithLockedHolding(
				order, account, holdingsByAccountId, quantity, executionPrice, amount, fee, reserved.total(),
				practiceContext.isPresent(), pricedAt);
		}
	}

	private void fillBuyWithLockedHolding(
		Order order, Account account, Map<Long, Holding> holdingsByAccountId, BigDecimal quantity,
		BigDecimal executionPrice, long amount, long fee, long reservedCash, boolean canonicalPracticeFill,
		LocalDateTime now) {
		Instrument instrument = order.getInstrument();

		if (instrument.isTutorialSample()) {
			TutorialAccount tutorialAccount = tutorialAccountService
				.getOrCreateForUpdate(account.getUser().getId(), account.getMarket(), now);
			if (canonicalPracticeFill) {
				tutorialAccount.releaseReservedCash(reservedCash);
				tutorialAccount.deductCash(amount + fee);
			} else {
				tutorialAccount.confirmReservedCash(amount + fee);
			}
		} else {
			if (canonicalPracticeFill) {
				account.releaseReservedCash(reservedCash);
				account.deductCash(amount + fee);
			} else {
				account.confirmReservedCash(amount + fee);
			}
		}

		Trade trade = Trade.of(
			order, account, instrument, null, order.getSide(), executionPrice, quantity, amount, fee, null, now, now);
		tradeRepository.save(trade);

		Holding holding = holdingsByAccountId.get(account.getId());
		if (holding == null) {
			holding = Holding.create(account, instrument, now);
		}
		Holding saved = portfolioBuyService
			.applyBuyTrade(account, instrument, trade, quantity, executionPrice, fee, now, holding);
		holdingsByAccountId.put(account.getId(), saved);

		order.markFilled();
		practiceOrderAttributionPort.createRiskSnapshotOnBuyFill(order, trade, now);
	}

	private void fillSellWithLockedHolding(
		Order order, Account account, Map<Long, Holding> holdingsByAccountId, BigDecimal quantity,
		BigDecimal executionPrice, long amount, long fee, LocalDateTime now) {
		Instrument instrument = order.getInstrument();

		Holding holding = holdingsByAccountId.get(account.getId());
		if (holding == null) {
			throw new IllegalStateException(
				"체결 대상 holding을 찾을 수 없습니다. accountId=" + account.getId() + ", instrumentId=" + instrument.getId());
		}
		holding.releaseReservedQuantity(quantity);

		Trade trade = Trade.of(
			order, account, instrument, null, order.getSide(), executionPrice, quantity, amount, fee, null, now, now);
		tradeRepository.save(trade);

		SellAllocationDto allocation = portfolioSellService.applySellTrade(holding, trade, quantity, now);
		portfolioSellService.finalizeSellRealizedPnl(account, trade, amount, fee, allocation, now);

		order.markFilled();
		if (!instrument.isTutorialSample()) {
			eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));
		}
	}

	private boolean isTriggered(Order order, BigDecimal canonicalPrice) {
		return order.getSide() == OrderSide.BUY
			? order.getLimitPrice().compareTo(canonicalPrice) >= 0
			: order.getLimitPrice().compareTo(canonicalPrice) <= 0;
	}
}
