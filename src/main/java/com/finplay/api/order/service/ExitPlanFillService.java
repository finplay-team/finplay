// OCO 손절·익절 예약 1건을 트리거 시점 현재가로 시장가 체결하는 서비스 — 잠금 순서 holding → plan(021 plan.md)
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanCondition;
import com.finplay.api.order.domain.ExitPlanConditionStatus;
import com.finplay.api.order.domain.ExitPlanConditionType;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.repository.ExitPlanConditionRepository;
import com.finplay.api.order.repository.ExitPlanRepository;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.PortfolioSellService;
import com.finplay.api.portfolio.service.SellAllocationDto;
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

/**
 * {@code docs/specs/021-general-risk-management-oco} plan.md "트리거·취소·잠금 순서" 표의 "가격 트리거" 행을
 * 구현한다. 잠금 순서는 사용자 취소({@link ExitPlanCancelService})와 동일한 {@code holding → plan}이라 데드락이
 * 없다. 트리거는 사용자 API 요청 문맥(Idempotency-Key, 요청 DTO)이 없는 서버 주도 경로라 {@code
 * OrderExecutionService}를 그대로 호출하지 않고, 여기서 직접 시장가 SELL 주문·체결을 생성한다 — 하위 조립
 * 블록({@code PortfolioSellService}의 lot 배분·실현손익 반영)은 기존 시장가·지정가 SELL과 그대로 재사용한다.
 */
@Service
@RequiredArgsConstructor
public class ExitPlanFillService {

	private final ExitPlanRepository exitPlanRepository;
	private final ExitPlanConditionRepository exitPlanConditionRepository;
	private final PortfolioSellService portfolioSellService;
	private final OrderRepository orderRepository;
	private final TradeRepository tradeRepository;
	private final Clock clock;
	private final ApplicationEventPublisher eventPublisher;

	@Transactional
	public void fillIfPending(Long exitPlanId, BigDecimal currentPrice) {
		// 존재 확인 겸 holding 참조 확보 — 잠그지 않은 조회(holding을 먼저 잠가야 하므로,
		// ExitPlanCancelService.cancel()과 동일 패턴).
		ExitPlan ownershipCheck = exitPlanRepository.findById(exitPlanId).orElse(null);
		if (ownershipCheck == null) {
			return;
		}

		// 잠금 순서 holding → plan — 사용자 취소·가격 트리거가 같은 순서를 쓰므로 데드락이 없다(021 plan.md).
		Holding holding = portfolioSellService.getHoldingForUpdate(
			ownershipCheck.getHolding().getAccount(), ownershipCheck.getInstrument());

		ExitPlan plan = exitPlanRepository.findByIdForUpdate(exitPlanId).orElse(null);
		if (plan == null || !plan.isPending()) {
			// 중복·역순 가격 이벤트 — 이미 종결된(terminal) plan은 no-op으로 skip한다(021 plan.md "정확히 한 번"
			// 규칙). plan 잠금에서 최초 커밋한 이벤트·취소만 승자가 되고 이후 도착하는 이벤트는 전부 여기서 걸린다.
			return;
		}

		// 잠근 plan의 확정 가격선으로 재판정한다 — 조회~잠금 사이 다른 트랜잭션이 먼저 종결시켰을 가능성은 위에서
		// 이미 걸러졌으므로, 이 재판정은 방어적 확인이다.
		ExitPlanConditionType triggeredType = resolveTriggeredCondition(plan, currentPrice);
		if (triggeredType == null) {
			return;
		}

		LocalDateTime now = LocalDateTime.now(clock);
		Order order = executeMarketSell(plan, holding, currentPrice, now);

		if (triggeredType == ExitPlanConditionType.TAKE_PROFIT) {
			plan.fillTakeProfit(order, now);
		} else {
			plan.fillStopLoss(order, now);
		}
		closeConditions(plan.getId(), triggeredType);
	}

	// 익절 currentPrice >= takeProfitPrice, 손절 currentPrice <= stopLossPrice(021 spec 비즈니스 규칙). 생성 시
	// 0 < stopLossPrice < entryPrice < takeProfitPrice가 보장되므로 두 조건을 동시에 만족할 수 없다.
	private ExitPlanConditionType resolveTriggeredCondition(ExitPlan plan, BigDecimal currentPrice) {
		if (currentPrice.compareTo(plan.getTakeProfitPrice()) >= 0) {
			return ExitPlanConditionType.TAKE_PROFIT;
		}
		if (currentPrice.compareTo(plan.getStopLossPrice()) <= 0) {
			return ExitPlanConditionType.STOP_LOSS;
		}
		return null;
	}

	// 트리거 시점 현재가로 서버가 직접 시장가 SELL 주문을 생성·체결한다 — 기존 시장가 매도(OrderExecutionService)와
	// 동일한 fee 계산(코인 전용, LimitOrderFeeCalculator 재사용)·lot 배분·실현손익 반영을 공유한다.
	private Order executeMarketSell(ExitPlan plan, Holding holding, BigDecimal currentPrice, LocalDateTime now) {
		Account account = holding.getAccount();
		Instrument instrument = plan.getInstrument();
		BigDecimal quantity = plan.getQuantity();

		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(quantity, currentPrice);
		long amount = reservation.amount();
		long fee = reservation.fee();

		String idempotencyKey = triggerIdempotencyKey(plan.getId());
		// Order.create는 MARKET 주문을 즉시 FILLED 상태로 만든다(OrderExecutionService와 동일) — 별도 markFilled
		// 호출이 필요 없다.
		Order order = Order.create(
			plan.getUser(), account, instrument, OrderSide.SELL, OrderType.MARKET, quantity, idempotencyKey,
			sha256Hex(idempotencyKey), now);
		orderRepository.save(order);

		Trade trade = Trade.of(
			order, account, instrument, null, OrderSide.SELL, currentPrice, quantity, amount, fee, null, now, now);
		tradeRepository.save(trade);

		holding.releaseReservedQuantity(quantity);
		SellAllocationDto allocation = portfolioSellService.applySellTrade(holding, trade, quantity, now);
		portfolioSellService.finalizeSellRealizedPnl(account, trade, amount, fee, allocation);

		// 커밋 이후(after-commit)에만 랭킹에 반영되도록 이벤트만 발행한다 — 기존 시장가·지정가 매도와 동일 훅 재사용.
		eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));
		return order;
	}

	// 트리거된 조건은 TRIGGERED, 반대쪽 PENDING 조건은 OCO 규칙으로 CANCELLED_BY_OCO로 전이한다.
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

	// 트리거 전용 시스템 주문 식별자 — 사용자 API 요청 문맥이 없으므로 exitPlanId 기반으로 결정적으로 생성한다.
	// 같은 plan은 holding → plan 잠금 아래 정확히 한 번만 체결되므로 uk_orders_user_idempotency와 충돌하지 않는다.
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
