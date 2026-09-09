package com.finplay.api.domain.order.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.domain.portfolio.service.SellAllocationDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
public class PracticeRunRestartOrderService {

	private static final BigDecimal STOCK_FEE_RATE = new BigDecimal("0.00015");
	private static final BigDecimal CRYPTO_FEE_RATE = new BigDecimal("0.0005");

	private final OrderRepository orderRepository;
	private final TradeRepository tradeRepository;
	private final AccountService accountService;
	private final TutorialAccountService tutorialAccountService;
	private final InstrumentService instrumentService;
	private final PortfolioSellService portfolioSellService;
	private final PracticeOrderSettlementService practiceOrderSettlementService;

	@Transactional
	public void cleanupCurrentRun(PracticeRunRestartCommand command) {
		List<Order> orders = orderRepository.findPracticeRunOrdersForUpdate(
			command.attemptId(), command.runNumber());
		if (command.instrumentId() == null) {
			if (!orders.isEmpty()) {
				throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
			}
			resetTutorialAccount(command);
			return;
		}

		Instrument instrument = instrumentService.getInstrumentEntity(command.instrumentId());
		validateInstrument(command, instrument);
		validateOrders(command, orders);

		Account account = accountService.getAccountForUpdate(
			command.userId(), command.market());
		validateOrderAccounts(account, orders);

		practiceOrderSettlementService.cancelCurrentRunExitPlans(
			command.userId(), command.attemptId(), command.runNumber());

		BigDecimal netFilledQuantity = calculateNetFilledQuantity(command);
		boolean pendingSellExists = orders.stream()
			.anyMatch(order -> order.getStatus() == OrderStatus.PENDING && order.getSide() == OrderSide.SELL);
		Holding holding = pendingSellExists || netFilledQuantity.signum() > 0
			? portfolioSellService.getHoldingForUpdate(account, instrument)
			: null;

		cancelPendingOrders(command, orders, holding);
		if (netFilledQuantity.signum() < 0) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		if (netFilledQuantity.signum() == 0) {
			resetTutorialAccount(command);
			return;
		}
		if (holding == null || holding.getAvailableQuantity().compareTo(netFilledQuantity) != 0) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}

		createCompensatingSell(command, account, instrument, holding, netFilledQuantity);
		resetTutorialAccount(command);
	}

	private void resetTutorialAccount(PracticeRunRestartCommand command) {
		tutorialAccountService.resetForUpdate(
			command.userId(),
			command.market(),
			command.restartedAt());
	}

	private BigDecimal calculateNetFilledQuantity(PracticeRunRestartCommand command) {
		BigDecimal net = BigDecimal.ZERO;
		for (Trade trade : tradeRepository.findFilledPracticeRunTrades(command.attemptId(), command.runNumber())) {
			net = trade.getSide() == OrderSide.BUY
				? net.add(trade.getQuantity())
				: net.subtract(trade.getQuantity());
		}
		return net;
	}

	private void cancelPendingOrders(PracticeRunRestartCommand command, List<Order> orders, Holding holding) {
		TutorialAccount tutorialAccount = null;
		for (Order order : orders) {
			if (order.getStatus() != OrderStatus.PENDING) {
				continue;
			}
			if (order.getOrderType() != OrderType.LIMIT || order.getLimitPrice() == null) {
				throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
			}
			if (order.getSide() == OrderSide.SELL) {
				if (holding == null) {
					throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
				}
				holding.releaseReservedQuantity(order.getQuantity());
			} else {
				if (tutorialAccount == null) {
					tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
						command.userId(), command.market(),
						command.restartedAt());
				}
				tutorialAccount.releaseReservedCash(
					LimitOrderFeeCalculator.calculate(order.getQuantity(), order.getLimitPrice()).total());
			}
			order.cancel();
		}
	}

	private void createCompensatingSell(
		PracticeRunRestartCommand command,
		Account account,
		Instrument instrument,
		Holding holding,
		BigDecimal quantity) {
		BigDecimal price = command.canonicalPrice();
		if (price == null || price.signum() <= 0) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		long amount = price.multiply(quantity).setScale(0, RoundingMode.FLOOR).longValueExact();
		BigDecimal feeRate = command.market() == Market.STOCK ? STOCK_FEE_RATE : CRYPTO_FEE_RATE;
		long fee = BigDecimal.valueOf(amount).multiply(feeRate)
			.setScale(0, RoundingMode.FLOOR).longValueExact();
		String idempotencyKey = "practice-restart:" + command.attemptId() + ":" + command.runNumber();

		Order order = Order.createForPracticeAttempt(
			account.getUser(), account, instrument, OrderSide.SELL, OrderType.MARKET, quantity,
			command.attemptId(), command.runNumber(), idempotencyKey, sha256(idempotencyKey), command.restartedAt());
		orderRepository.save(order);
		Trade trade = Trade.of(
			order, account, instrument, null, OrderSide.SELL, price, quantity, amount, fee, null,
			command.restartedAt(), command.restartedAt());
		tradeRepository.save(trade);

		SellAllocationDto allocation = portfolioSellService.applySellTrade(
			holding, trade, quantity, command.restartedAt());
		portfolioSellService.finalizeSellRealizedPnl(account, trade, amount, fee, allocation, command.restartedAt());
	}

	private void validateInstrument(PracticeRunRestartCommand command, Instrument instrument) {
		if (!instrument.isTutorialSample() || instrument.getMarket() != command.market()) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
	}

	private void validateOrders(PracticeRunRestartCommand command, List<Order> orders) {
		for (Order order : orders) {
			if (!order.getUser().getId().equals(command.userId())
				|| order.getInstrument().getId().longValue() != command.instrumentId().longValue()) {
				throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
			}
		}
	}

	private void validateOrderAccounts(Account account, List<Order> orders) {
		if (orders.stream().anyMatch(order -> !order.getAccount().getId().equals(account.getId()))) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
		}
	}
}
