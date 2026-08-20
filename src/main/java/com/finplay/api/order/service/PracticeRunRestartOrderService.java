// attempt 선잠금 뒤 현재 실행 주문·예약·보유를 원자 정리하고 보상 매도 원장을 남기는 order 서비스
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.TutorialAccount;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.account.service.TutorialAccountService;
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
	private final TutorialAccountService tutorialAccountService;
	private final InstrumentService instrumentService;
	private final PortfolioSellService portfolioSellService;
	private final PracticeOrderSettlementService practiceOrderSettlementService;

	@Transactional
	public void cleanupCurrentRun(PracticeRunRestartCommand command) {
		// 호출부가 attempt를 선잠금한 상태다. 이후 현재 run 주문을 한 번에 ID 오름차순으로 잠근다.
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
			command.userId(), com.finplay.api.account.domain.Market.valueOf(command.market().name()));
		validateOrderAccounts(account, orders);

		// 042 EXITPRESET-015 — **예약 취소가 주문 취소보다 먼저다.** 예약 수량이 남아 있으면 아래 보상 매도가
		// availableQuantity 부족으로 실패한다. 취소 서비스가 flush 후 holding을 detach하므로, 이 호출 뒤에
		// holding을 처음 잡는 아래 순서를 지켜야 낡은 인스턴스를 재사용하지 않는다.
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
		// 보상매도(튜토리얼 종목이면 튜토리얼 계좌 현금·realizedPnl 증가, 직전 항목에서 완료)가 반영된 뒤
		// 절대값 리셋을 마지막에 걸어, 그 증가분까지 포함해 정확히 초기값으로 되돌린다(TUTORIAL-CASH-ISOL-006).
		resetTutorialAccount(command);
	}

	// 재시작마다 그 사용자·시장의 튜토리얼 계좌를 현금 1000만원·예약 현금 0원·realizedPnl 0원으로 초기화한다.
	// cleanupCurrentRun의 모든 성공 경로(주문 미선택/순체결수량 0/보상매도 완료) 끝에서 호출된다.
	private void resetTutorialAccount(PracticeRunRestartCommand command) {
		tutorialAccountService.resetForUpdate(
			command.userId(),
			com.finplay.api.account.domain.Market.valueOf(command.market().name()),
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
				// validateInstrument가 이 메서드 도달 전 instrument.isTutorialSample()을 이미 강제하므로,
				// 여기 도달하는 지정가 매수 PENDING 예약은 전부 튜토리얼 계좌에 걸려 있다(PR #452 리뷰 차단 1번 —
				// PracticeLimitOrderCreationService/LimitOrderCreationService가 샌드박스 매수 예약을 튜토리얼
				// 계좌로 옮긴 것과 짝이 맞아야 한다).
				if (tutorialAccount == null) {
					tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
						command.userId(), com.finplay.api.account.domain.Market.valueOf(command.market().name()),
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
