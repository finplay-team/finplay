// 코인 지정가 매수·매도 주문의 검증·예약(에스크로)·PENDING 저장을 하나의 트랜잭션으로 처리하는 서비스
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.order.dto.response.LimitOrderResponse;
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
public class LimitOrderCreationService {

	private final UserQueryService userQueryService;
	private final AccountService accountService;
	private final InstrumentService instrumentService;
	private final PortfolioSellService portfolioSellService;
	private final OrderRepository orderRepository;
	private final Clock clock;

	// plan.md "지정가 생성 흐름" — BUY는 account 락만, SELL은 holding 락만 잡는다.
	@Transactional
	public LimitOrderResponse execute(
		Long userId, String idempotencyKey, String requestHash, LimitOrderCreateRequest request) {
		validateMarketIsCrypto(request.market());
		Instrument instrument = getValidatedInstrument(request.market(), request.instrumentId());
		validateQuantityFormat(request.quantity());
		validateLimitPrice(request.limitPrice());
		validateMinOrderAmount(request.quantity(), request.limitPrice(), instrument);

		return request.side() == OrderSide.SELL
			? createSellOrder(userId, idempotencyKey, requestHash, request, instrument)
			: createBuyOrder(userId, idempotencyKey, requestHash, request, instrument);
	}

	private LimitOrderResponse createBuyOrder(
		Long userId, String idempotencyKey, String requestHash, LimitOrderCreateRequest request,
		Instrument instrument) {
		BigDecimal quantity = request.quantity();
		BigDecimal limitPrice = request.limitPrice();

		// LMT-001 BUY: account 락만 잡는다(holding은 건드리지 않는다).
		Account account = accountService.getAccountForUpdate(userId, toAccountMarket(request.market()));

		long cashRequired = LimitOrderFeeCalculator.calculate(quantity, limitPrice).total();
		if (account.getAvailableCash() < cashRequired) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
		}
		account.reserveCash(cashRequired);

		Order order = saveLimitPendingOrder(
			userId, idempotencyKey, requestHash, request, instrument, account, quantity, limitPrice);
		return LimitOrderResponse.from(order);
	}

	private LimitOrderResponse createSellOrder(
		Long userId, String idempotencyKey, String requestHash, LimitOrderCreateRequest request,
		Instrument instrument) {
		BigDecimal quantity = request.quantity();
		BigDecimal limitPrice = request.limitPrice();

		// LMT-001 SELL: 계좌 락 없이 holding 락만 잡는다 — 두 자원을 동시에 들지 않으므로 ABBA 위험이 없다(plan.md).
		Account account = accountService.getAccountFor(userId, toAccountMarket(request.market()));
		Holding holding = portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity);
		holding.reserveQuantity(quantity);

		Order order = saveLimitPendingOrder(
			userId, idempotencyKey, requestHash, request, instrument, account, quantity, limitPrice);
		return LimitOrderResponse.from(order);
	}

	private Order saveLimitPendingOrder(
		Long userId, String idempotencyKey, String requestHash, LimitOrderCreateRequest request,
		Instrument instrument, Account account, BigDecimal quantity, BigDecimal limitPrice) {
		User user = userQueryService.getUser(userId);
		LocalDateTime now = LocalDateTime.now(clock);

		Order order = Order.createLimitPending(
			user, account, instrument, request.side(), quantity, limitPrice, idempotencyKey, requestHash, now);
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

	// PracticeLimitOrderCreationService(030)가 교육 지정가 검증에 그대로 재사용한다 — 패키지 전용 접근이라
	// order.service 밖으로는 노출되지 않는다(plan.md "기존 수량·가격·최소금액·현금 예약 검증 재사용").
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

	// spec.md: 수량×지정가(내림 전 금액) 기준으로 기존 ORD-003 최소주문금액 규칙을 재사용한다.
	static void validateMinOrderAmount(BigDecimal quantity, BigDecimal limitPrice, Instrument instrument) {
		BigDecimal rawAmount = quantity.multiply(limitPrice);
		if (rawAmount.compareTo(BigDecimal.valueOf(instrument.getMinOrderAmount())) < 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 최소 주문금액에 미달합니다.");
		}
	}

	// 설계 노트(OrderExecutionService.getAccountFor와 동일) — 계좌 조회 시에만 두 Market enum 사이를 값 기반으로 변환한다.
	private com.finplay.api.account.domain.Market toAccountMarket(Market market) {
		return com.finplay.api.account.domain.Market.valueOf(market.name());
	}
}
