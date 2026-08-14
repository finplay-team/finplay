// attempt 선잠금 뒤 현재 실행 주문·예약·보유를 원자 정리하고 보상 매도 원장을 남기는 order 서비스
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.PortfolioSellService;
import com.finplay.api.portfolio.service.SellAllocationDto;
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
	private final InstrumentService instrumentService;
	private final PortfolioSellService portfolioSellService;

	@Transactional
	public void cleanupCurrentRun(PracticeRunRestartCommand command) {
		// 호출부가 attempt를 선잠금한 상태다. 이후 현재 run 주문을 한 번에 ID 오름차순으로 잠근다.
		List<Order> orders = orderRepository.findPracticeRunOrdersForUpdate(
			command.attemptId(), command.runNumber());
		if (command.instrumentId() == null) {
			if (!orders.isEmpty()) {
				throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
			}
			return;
		}

		Instrument instrument = instrumentService.getInstrumentEntity(command.instrumentId());
		validateInstrument(command, instrument);
		validateOrders(command, orders);

		Account account = accountService.getAccountForUpdate(
			command.userId(), com.finplay.api.account.domain.Market.valueOf(command.market().name()));
		validateOrderAccounts(account, orders);
		BigDecimal netFilledQuantity = calculateNetFilledQuantity(command);
		boolean pendingSellExists = orders.stream()
			.anyMatch(order -> order.getStatus() == OrderStatus.PENDING && order.getSide() == OrderSide.SELL);
		Holding holding = pendingSellExists || netFilledQuantity.signum() > 0
			? portfolioSellService.getHoldingForUpdate(account, instrument)
			: null;

		cancelPendingOrders(orders, account, holding);
		if (netFilledQuantity.signum() < 0) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		if (netFilledQuantity.signum() == 0) {
			return;
		}
		if (holding == null || holding.getAvailableQuantity().compareTo(netFilledQuantity) != 0) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}

		createCompensatingSell(command, account, instrument, holding, netFilledQuantity);
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

	private void cancelPendingOrders(List<Order> orders, Account account, Holding holding) {
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
				account.releaseReservedCash(
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
		portfolioSellService.finalizeSellRealizedPnl(account, trade, amount, fee, allocation);
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
