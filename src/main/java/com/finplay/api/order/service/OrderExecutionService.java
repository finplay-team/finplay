// 시장가 매수·매도 주문의 검증·가격조회·체결·계좌/보유 갱신을 하나의 트랜잭션으로 처리하는 서비스
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.OrderExecutionPriceDto;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.PortfolioBuyService;
import com.finplay.api.portfolio.service.PortfolioSellService;
import com.finplay.api.portfolio.service.SellAllocationDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderExecutionService {

	private static final String MARKET_ORDER_TYPE = "MARKET";
	// 매직 넘버 금지 컨벤션 — plan.md 설계 노트 5 확정값
	private static final BigDecimal STOCK_FEE_RATE = new BigDecimal("0.00015");
	private static final BigDecimal CRYPTO_FEE_RATE = new BigDecimal("0.0005");

	private final UserQueryService userQueryService;
	private final AccountService accountService;
	private final InstrumentService instrumentService;
	private final PriceQueryService priceQueryService;
	private final PortfolioBuyService portfolioBuyService;
	private final PortfolioSellService portfolioSellService;
	private final OrderRepository orderRepository;
	private final TradeRepository tradeRepository;
	private final Clock clock;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public OrderResponse execute(
		Long userId, String idempotencyKey, String requestHash, OrderCreateRequest request) {
		validateOrderType(request.orderType());

		Instrument instrument = getValidatedInstrument(request.market(), request.instrumentId());
		validateQuantityFormat(request.market(), request.quantity());

		// 계좌 선조회를 제거했다 — 매수·매도 모두 각자 계좌를 잠가 조회한다(호출 시점·인자만 다름, 이슈 #224).
		return request.side() == OrderSide.SELL
			? createSellOrder(userId, idempotencyKey, requestHash, request, instrument)
			: createBuyOrder(userId, idempotencyKey, requestHash, request, instrument);
	}

	private OrderResponse createBuyOrder(
		Long userId, String idempotencyKey, String requestHash, OrderCreateRequest request, Instrument instrument) {
		BigDecimal quantity = request.quantity();
		// 매수도 매도와 동일하게 계좌를 먼저 잠근다(spec.md "시장가 매수 경로 락 보강", 이슈 #224).
		Account account = getAccountForUpdateFor(userId, request.market());

		// 설계 노트 2: 매수 최소구현 견본 — marketStatus·가격·세션 단일 관측→최소금액→amount/fee 계산(공유)
		OrderPricing pricing = priceOrder(request.market(), instrument, quantity);
		long cashRequired = pricing.amount() + pricing.fee();
		if (account.getAvailableCash() < cashRequired) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
		}

		User user = userQueryService.getUser(userId);
		LocalDateTime now = LocalDateTime.now(clock);

		Order order = Order.create(
			user,
			account,
			instrument,
			request.side(),
			OrderType.MARKET,
			quantity,
			idempotencyKey,
			requestHash,
			now);
		orderRepository.save(order);

		Trade trade = Trade.of(
			order, account, instrument, pricing.stockReplaySession(), request.side(), pricing.price(), quantity,
			pricing.amount(), pricing.fee(),
			null, now, now);
		tradeRepository.save(trade);

		account.deductCash(cashRequired);
		// 샌드박스(튜토리얼) 종목 매수의 현금 순변동은 사용자에게 보이는 평가자산에서 나중에 제외할 수
		// 있도록 별도로 누적해 둔다(spec 033 SANDBOX-EXCL-006).
		if (instrument.isTutorialSample()) {
			account.addSandboxCashAdjustment(-cashRequired);
		}

		portfolioBuyService.applyBuyTrade(account, instrument, trade, quantity, pricing.price(), pricing.fee(), now);

		return OrderResponse.of(order, trade);
	}

	private OrderResponse createSellOrder(
		Long userId, String idempotencyKey, String requestHash, OrderCreateRequest request, Instrument instrument) {
		BigDecimal quantity = request.quantity();

		// 잠금 순서를 지정가 체결(LimitOrderFillService)과 맞춘다 — 실제로 경합하는 두 자원인 account·holding에
		// 대해 항상 account를 먼저 잠그고 holding을 잠근다(spec.md 완료조건, 015-limit-order 항목5).
		// 설계 노트 2: 가격조회 전에 계좌·보유수량부터 검증해 불필요한 시세 조회를 피한다.
		Account account = getAccountForUpdateFor(userId, request.market());
		Holding holding = portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity);

		OrderPricing pricing = priceOrder(request.market(), instrument, quantity);

		User user = userQueryService.getUser(userId);
		LocalDateTime now = LocalDateTime.now(clock);

		Order order = Order.create(
			user,
			account,
			instrument,
			request.side(),
			OrderType.MARKET,
			quantity,
			idempotencyKey,
			requestHash,
			now);
		orderRepository.save(order);

		// 실현손익은 lot 배분이 끝난 뒤에만 계산 가능하므로 최초 저장 시 null.
		Trade trade = Trade.of(
			order, account, instrument, pricing.stockReplaySession(), request.side(), pricing.price(), quantity,
			pricing.amount(), pricing.fee(),
			null, now, now);
		tradeRepository.save(trade);

		SellAllocationDto allocation = portfolioSellService.applySellTrade(holding, trade, quantity, now);

		// 설계 노트 4: realizedPnl = (매도금액 - 매도수수료) - (배분된 매수원가 합 + 배분된 매수수수료 합)
		// (이 공식은 PortfolioSellService.finalizeSellRealizedPnl로도 추출돼 015-limit-order LMT-002 지정가 체결이
		// 재사용한다 — 여기 시장가 경로는 기존 동작·테스트를 그대로 보존하기 위해 인라인 계산을 유지한다.)
		long realizedPnl = (pricing.amount() - pricing.fee())
			- (allocation.totalAllocatedCost() + allocation.totalAllocatedBuyFee());
		trade.fillRealizedPnl(realizedPnl);

		account.addCash(pricing.amount() - pricing.fee());
		// 샌드박스(튜토리얼) 종목 매도 손익은 계좌 집계(랭킹 score)에 반영하지 않는다(spec 033
		// SANDBOX-EXCL-004). trade.realizedPnl은 원장 값이라 항상 채운다.
		if (!instrument.isTutorialSample()) {
			account.addRealizedPnl(realizedPnl);
		} else {
			// 샌드박스 매도의 현금 입금도 같은 조건으로 별도 누적한다(spec 033 SANDBOX-EXCL-006).
			account.addSandboxCashAdjustment(pricing.amount() - pricing.fee());
		}
		// 커밋 이후(after-commit)에만 랭킹에 반영되도록 이벤트만 발행한다 — 손익값을 싣지 않고 이벤트 처리 시점에
		// DB에서 최신 realizedPnl을 다시 조회한다(동시성 경합 Decision Gate, plan.md).
		eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));

		return OrderResponse.of(order, trade);
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
		// tradable=false 종목은 이 경로로 체결될 수 없다(031 SANDBOX-001, 이슈 #339 PR #341 리뷰 차단사항).
		// 이 검증이 없던 시절엔 tradable=false인 실제 종목이 하나도 없어 빈틈이 드러나지 않았을 뿐이다 —
		// 031이 처음으로 tradable=false 샘플 종목을 만들면서 즐겨찾기(POST /api/favorites)는 이미 막혀 있는데
		// 이 일반 주문 경로만 뚫려 있던 것을 여기서 함께 막는다.
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

	// 시장가 매수·매도 모두 계좌를 잠가 조회한다(015-limit-order 항목5, 이슈 #224) — 지정가 체결
	// (LimitOrderFillService)과 account→holding 잠금 순서를 맞춰 ABBA 데드락을 막는다.
	private Account getAccountForUpdateFor(Long userId, Market market) {
		com.finplay.api.account.domain.Market accountMarket = com.finplay.api.account.domain.Market
			.valueOf(market.name());
		return accountService.getAccountForUpdate(userId, accountMarket);
	}

	// 설계 노트 1: getOrderExecutionPrice 한 관측에서 marketStatus·가격·세션을 확정한 뒤 최소주문금액 검증과
	// amount/fee 계산(FLOOR)을 매수·매도가 공유한다.
	private OrderPricing priceOrder(Market market, Instrument instrument, BigDecimal quantity) {
		OrderExecutionPriceDto executionPrice = priceQueryService.getOrderExecutionPrice(instrument);
		PriceQuoteDto priceQuote = executionPrice.priceQuote();
		BigDecimal price = priceQuote.price();
		BigDecimal rawAmount = price.multiply(quantity);

		validateMinOrderAmount(market, rawAmount, instrument);

		long amount = rawAmount.setScale(0, RoundingMode.FLOOR).longValueExact();
		BigDecimal feeRate = market == Market.STOCK ? STOCK_FEE_RATE : CRYPTO_FEE_RATE;
		long fee = BigDecimal.valueOf(amount).multiply(feeRate).setScale(0, RoundingMode.FLOOR).longValueExact();
		return new OrderPricing(price, amount, fee, executionPrice.stockReplaySession());
	}

	// 설계 노트 1: 코인 최소주문금액 검증(내림 전 금액으로 비교). 매수·매도 공유 — 사람 확인 결과 대칭 적용 확정.
	private void validateMinOrderAmount(Market market, BigDecimal rawAmount, Instrument instrument) {
		if (market == Market.CRYPTO
			&& rawAmount.compareTo(BigDecimal.valueOf(instrument.getMinOrderAmount())) < 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 최소 주문금액에 미달합니다.");
		}
	}

	// 가격조회 결과(체결가·확정금액·수수료)를 매수·매도 분기에 함께 전달하는 내부 값 객체
	private record OrderPricing(
		BigDecimal price,
		long amount,
		long fee,
		com.finplay.api.market.domain.StockReplaySession stockReplaySession) {
	}
}
