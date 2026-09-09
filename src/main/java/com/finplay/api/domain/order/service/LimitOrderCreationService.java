package com.finplay.api.domain.order.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LimitOrderCreationService {

	private final UserQueryService userQueryService;
	private final AccountService accountService;
	private final TutorialAccountService tutorialAccountService;
	private final InstrumentService instrumentService;
	private final PortfolioSellService portfolioSellService;
	private final OrderRepository orderRepository;
	private final PracticeOrderAttributionPort practiceOrderAttributionPort;
	private final PracticeOrderSettlementService practiceOrderSettlementService;
	private final Clock clock;

	@Transactional
	public LimitOrderResponse execute(
		Long userId, String idempotencyKey, String requestHash, LimitOrderCreateRequest request) {
		validateMarketIsCrypto(request.market());
		Instrument instrument = getValidatedInstrument(request.market(), request.instrumentId());
		validateQuantityFormat(request.quantity());
		validateLimitPrice(request.limitPrice());
		validateMinOrderAmount(request.quantity(), request.limitPrice(), instrument);
		Optional<PracticeOrderAttributionDto> practiceAttribution = practiceOrderAttributionPort
			.lockForOrder(userId, instrument, OrderType.LIMIT);

		return request.side() == OrderSide.SELL
			? createSellOrder(userId, idempotencyKey, requestHash, request, instrument, practiceAttribution)
			: createBuyOrder(userId, idempotencyKey, requestHash, request, instrument, practiceAttribution);
	}

	private LimitOrderResponse createBuyOrder(
		Long userId, String idempotencyKey, String requestHash, LimitOrderCreateRequest request,
		Instrument instrument, Optional<PracticeOrderAttributionDto> practiceAttribution) {
		BigDecimal quantity = request.quantity();
		BigDecimal limitPrice = request.limitPrice();

		Account account = accountService.getAccountForUpdate(userId, request.market());

		long cashRequired = LimitOrderFeeCalculator.calculate(quantity, limitPrice).total();
		if (instrument.isTutorialSample()) {
			LocalDateTime now = LocalDateTime.now(clock);
			TutorialAccount tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
				userId, request.market(), now);
			if (tutorialAccount.getAvailableCash() < cashRequired) {
				throw new BusinessException(ErrorCode.TUTORIAL_INSUFFICIENT_CASH);
			}
			tutorialAccount.reserveCash(cashRequired);
		} else {
			if (account.getAvailableCash() < cashRequired) {
				throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
			}
			account.reserveCash(cashRequired);
		}

		Order order = saveLimitPendingOrder(
			userId, idempotencyKey, requestHash, request, instrument, account, quantity, limitPrice,
			practiceAttribution);
		return LimitOrderResponse.from(order);
	}

	private LimitOrderResponse createSellOrder(
		Long userId, String idempotencyKey, String requestHash, LimitOrderCreateRequest request,
		Instrument instrument, Optional<PracticeOrderAttributionDto> practiceAttribution) {
		BigDecimal quantity = request.quantity();
		BigDecimal limitPrice = request.limitPrice();

		Account account = accountService.getAccountFor(userId, request.market());
		practiceAttribution.ifPresent(attribution -> practiceOrderSettlementService.cancelCurrentRunExitPlans(
			userId, attribution.attemptId(), attribution.runNumber()));
		Holding holding = portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity);
		holding.reserveQuantity(quantity);

		Order order = saveLimitPendingOrder(
			userId, idempotencyKey, requestHash, request, instrument, account, quantity, limitPrice,
			practiceAttribution);
		return LimitOrderResponse.from(order);
	}

	private Order saveLimitPendingOrder(
		Long userId, String idempotencyKey, String requestHash, LimitOrderCreateRequest request,
		Instrument instrument, Account account, BigDecimal quantity, BigDecimal limitPrice,
		Optional<PracticeOrderAttributionDto> practiceAttribution) {
		User user = userQueryService.getUser(userId);
		LocalDateTime now = LocalDateTime.now(clock);

		Order order = practiceAttribution
			.map(attribution -> Order.createLimitPendingForPracticeAttempt(
				user, account, instrument, request.side(), quantity, limitPrice,
				attribution.attemptId(), attribution.runNumber(), idempotencyKey, requestHash, now))
			.orElseGet(() -> Order.createLimitPending(
				user, account, instrument, request.side(), quantity, limitPrice, idempotencyKey, requestHash, now));
		orderRepository.save(order);
		return order;
	}

	private void validateMarketIsCrypto(Market market) {
		if (market != Market.CRYPTO) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 종목만 지정가 주문을 지원합니다.");
		}
	}

	private Instrument getValidatedInstrument(Market market, Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		if (instrument.getMarket() != market) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "요청한 시장과 종목의 시장이 일치하지 않습니다.");
		}
		return instrument;
	}

	static void validateQuantityFormat(BigDecimal quantity) {
		if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "수량은 0보다 커야 합니다.");
		}
		if (quantity.stripTrailingZeros().scale() > 8) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 수량은 소수점 8자리 이하여야 합니다.");
		}
	}

	static void validateLimitPrice(BigDecimal limitPrice) {
		if (limitPrice.compareTo(BigDecimal.ZERO) <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "지정가는 0보다 커야 합니다.");
		}
	}

	static void validateMinOrderAmount(BigDecimal quantity, BigDecimal limitPrice, Instrument instrument) {
		BigDecimal rawAmount = quantity.multiply(limitPrice);
		if (rawAmount.compareTo(BigDecimal.valueOf(instrument.getMinOrderAmount())) < 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 최소 주문금액에 미달합니다.");
		}
	}
}
