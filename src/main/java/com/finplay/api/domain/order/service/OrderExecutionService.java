package com.finplay.api.domain.order.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.OrderExecutionPriceDto;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderExecutionService {

	private static final String MARKET_ORDER_TYPE = "MARKET";
	private static final BigDecimal STOCK_FEE_RATE = new BigDecimal("0.00015");
	private static final BigDecimal CRYPTO_FEE_RATE = new BigDecimal("0.0005");

	private final UserQueryService userQueryService;
	private final AccountService accountService;
	private final TutorialAccountService tutorialAccountService;
	private final InstrumentService instrumentService;
	private final PriceQueryService priceQueryService;
	private final PortfolioBuyService portfolioBuyService;
	private final PortfolioSellService portfolioSellService;
	private final OrderRepository orderRepository;
	private final TradeRepository tradeRepository;
	private final PracticeOrderAttributionPort practiceOrderAttributionPort;
	private final PracticeOrderSettlementService practiceOrderSettlementService;
	private final Clock clock;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public OrderResponse execute(
		Long userId, String idempotencyKey, String requestHash, OrderCreateRequest request) {
		validateOrderType(request.orderType());

		Instrument instrument = getValidatedInstrument(request.market(), request.instrumentId());
		validateQuantityFormat(request.market(), request.quantity());
		Optional<PracticeOrderAttributionDto> practiceAttribution = practiceOrderAttributionPort
			.lockForOrder(userId, instrument, OrderType.MARKET);

		return request.side() == OrderSide.SELL
			? createSellOrder(userId, idempotencyKey, requestHash, request, instrument, practiceAttribution)
			: createBuyOrder(userId, idempotencyKey, requestHash, request, instrument, practiceAttribution);
	}

	private OrderResponse createBuyOrder(
		Long userId,
		String idempotencyKey,
		String requestHash,
		OrderCreateRequest request,
		Instrument instrument,
		Optional<PracticeOrderAttributionDto> practiceAttribution) {
		BigDecimal quantity = request.quantity();
		Account account = getAccountForUpdateFor(userId, request.market());

		OrderPricing pricing = priceOrder(request.market(), instrument, quantity, practiceAttribution);
		long cashRequired = pricing.amount() + pricing.fee();

		LocalDateTime now = LocalDateTime.now(clock);

		TutorialAccount tutorialAccount = instrument.isTutorialSample()
			? tutorialAccountService.getOrCreateForUpdate(userId, request.market(), now)
			: null;
		if (tutorialAccount != null) {
			if (tutorialAccount.getAvailableCash() < cashRequired) {
				throw new BusinessException(ErrorCode.TUTORIAL_INSUFFICIENT_CASH);
			}
		} else if (account.getAvailableCash() < cashRequired) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
		}

		User user = userQueryService.getUser(userId);

		Order order = createOrder(
			user, account, instrument, request, quantity, practiceAttribution, idempotencyKey, requestHash, now);
		orderRepository.save(order);

		Trade trade = Trade.of(
			order, account, instrument, pricing.stockReplaySession(), request.side(), pricing.price(), quantity,
			pricing.amount(), pricing.fee(),
			null, now, now);
		tradeRepository.save(trade);

		if (tutorialAccount != null) {
			tutorialAccount.deductCash(cashRequired);
		} else {
			account.deductCash(cashRequired);
		}

		portfolioBuyService.applyBuyTrade(account, instrument, trade, quantity, pricing.price(), pricing.fee(), now);
		practiceOrderAttributionPort.createRiskSnapshotOnBuyFill(order, trade, now);

		return OrderResponse.of(order, trade);
	}

	private OrderResponse createSellOrder(
		Long userId,
		String idempotencyKey,
		String requestHash,
		OrderCreateRequest request,
		Instrument instrument,
		Optional<PracticeOrderAttributionDto> practiceAttribution) {
		BigDecimal quantity = request.quantity();

		Account account = getAccountForUpdateFor(userId, request.market());
		practiceAttribution.ifPresent(attribution -> practiceOrderSettlementService.cancelCurrentRunExitPlans(
			userId, attribution.attemptId(), attribution.runNumber()));
		Holding holding = portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity);

		OrderPricing pricing = priceOrder(request.market(), instrument, quantity, practiceAttribution);

		User user = userQueryService.getUser(userId);
		LocalDateTime now = LocalDateTime.now(clock);

		Order order = createOrder(
			user, account, instrument, request, quantity, practiceAttribution, idempotencyKey, requestHash, now);
		orderRepository.save(order);

		Trade trade = Trade.of(
			order, account, instrument, pricing.stockReplaySession(), request.side(), pricing.price(), quantity,
			pricing.amount(), pricing.fee(),
			null, now, now);
		tradeRepository.save(trade);

		SellAllocationDto allocation = portfolioSellService.applySellTrade(holding, trade, quantity, now);

		long realizedPnl = (pricing.amount() - pricing.fee())
			- (allocation.totalAllocatedCost() + allocation.totalAllocatedBuyFee());
		trade.fillRealizedPnl(realizedPnl);

		if (!instrument.isTutorialSample()) {
			account.addCash(pricing.amount() - pricing.fee());
			account.addRealizedPnl(realizedPnl);
		} else {
			TutorialAccount tutorialAccount = tutorialAccountService
				.getOrCreateForUpdate(userId, request.market(), now);
			tutorialAccount.addCash(pricing.amount() - pricing.fee());
			tutorialAccount.addRealizedPnl(realizedPnl);
		}
		if (!instrument.isTutorialSample()) {
			eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));
		}

		return OrderResponse.of(order, trade);
	}

	private Order createOrder(
		User user,
		Account account,
		Instrument instrument,
		OrderCreateRequest request,
		BigDecimal quantity,
		Optional<PracticeOrderAttributionDto> practiceAttribution,
		String idempotencyKey,
		String requestHash,
		LocalDateTime now) {
		return practiceAttribution
			.map(attribution -> Order.createForPracticeAttempt(
				user,
				account,
				instrument,
				request.side(),
				OrderType.MARKET,
				quantity,
				attribution.attemptId(),
				attribution.runNumber(),
				idempotencyKey,
				requestHash,
				now))
			.orElseGet(() -> Order.create(
				user,
				account,
				instrument,
				request.side(),
				OrderType.MARKET,
				quantity,
				idempotencyKey,
				requestHash,
				now));
	}

	private void validateOrderType(String orderType) {
		if (!MARKET_ORDER_TYPE.equals(orderType)) {
			throw new BusinessException(ErrorCode.UNSUPPORTED_ORDER_TYPE);
		}
	}

	private Instrument getValidatedInstrument(Market market, Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		if (instrument.getMarket() != market) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "요청한 시장과 종목의 시장이 일치하지 않습니다.");
		}
		if (!instrument.isTradable()) {
			throw new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE);
		}
		return instrument;
	}

	private void validateQuantityFormat(Market market, BigDecimal quantity) {
		if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "수량은 0보다 커야 합니다.");
		}
		if (market == Market.STOCK && quantity.stripTrailingZeros().scale() > 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "주식 수량은 정수여야 합니다.");
		}
		if (market == Market.CRYPTO && quantity.stripTrailingZeros().scale() > 8) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 수량은 소수점 8자리 이하여야 합니다.");
		}
	}

	private Account getAccountForUpdateFor(Long userId, Market market) {
		return accountService.getAccountForUpdate(userId, market);
	}

	private OrderPricing priceOrder(
		Market market,
		Instrument instrument,
		BigDecimal quantity,
		Optional<PracticeOrderAttributionDto> practiceAttribution) {
		OrderExecutionPriceDto executionPrice = practiceAttribution.isPresent()
			? null
			: priceQueryService.getOrderExecutionPrice(instrument);
		BigDecimal price = practiceAttribution
			.map(PracticeOrderAttributionDto::canonicalPrice)
			.orElseGet(() -> executionPrice.priceQuote().price());
		BigDecimal rawAmount = price.multiply(quantity);

		validateMinOrderAmount(market, rawAmount, instrument);

		long amount = rawAmount.setScale(0, RoundingMode.FLOOR).longValueExact();
		BigDecimal feeRate = market == Market.STOCK ? STOCK_FEE_RATE : CRYPTO_FEE_RATE;
		long fee = BigDecimal.valueOf(amount).multiply(feeRate).setScale(0, RoundingMode.FLOOR).longValueExact();
		return new OrderPricing(
			price, amount, fee, executionPrice == null ? null : executionPrice.stockReplaySession());
	}

	private void validateMinOrderAmount(Market market, BigDecimal rawAmount, Instrument instrument) {
		if (market == Market.CRYPTO
			&& rawAmount.compareTo(BigDecimal.valueOf(instrument.getMinOrderAmount())) < 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 최소 주문금액에 미달합니다.");
		}
	}

	private record OrderPricing(
		BigDecimal price,
		long amount,
		long fee,
		com.finplay.api.domain.market.entity.StockReplaySession stockReplaySession) {
	}
}
