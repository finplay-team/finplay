// OCO 손절·익절 예약 1건을 트리거 시점 현재가로 시장가 체결하는 서비스 — 잠금 순서 account → holding → plan
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

/**
 * {@code ai/specs/021-general-risk-management-oco} plan.md "트리거·취소·잠금 순서" 표의 "가격 트리거" 행을
 * 구현한다. 그 표의 {@code holding → plan}은 예약수량 원장(reservedQuantity)에 대한 잠금 순서를 규정한 것이고,
 * 현금·실현손익 갱신은 기존 시장가 매도({@code OrderExecutionService})·지정가 체결({@code LimitOrderFillService})과
 * 동일한 관례를 따라 {@code account}를 가장 먼저 잠근다 — 전체 잠금 순서는 {@code account → holding → plan}이다
 * (PR #349 리뷰 차단 수정). 트리거는 사용자 API 요청 문맥(Idempotency-Key, 요청 DTO)이 없는 서버 주도 경로라
 * {@code OrderExecutionService}를 그대로 호출하지 않고, 여기서 직접 시장가 SELL 주문·체결을 생성한다 — 하위 조립
 * 블록({@code PortfolioSellService}의 lot 배분·실현손익 반영)은 기존 시장가·지정가 SELL과 그대로 재사용한다.
 */
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
		// 존재 확인 겸 account·holding 참조 확보 — 잠그지 않은 조회(account를 먼저 잠가야 하므로).
		ExitPlan ownershipCheck = exitPlanRepository.findById(exitPlanId).orElse(null);
		if (ownershipCheck == null) {
			return;
		}

		// ownershipCheck.getHolding()(non-id 접근)이 holding을 이미 1급 캐시에 올려둔다 — 이후 잠금 쿼리(FOR
		// UPDATE)가 실행돼도 Hibernate는 이미 세션에 있는 같은 id 인스턴스를 필드 갱신 없이 그대로 반환하므로,
		// 잠금 직전까지 다른 트랜잭션이 커밋한 변경(reservedQuantity·status)을 보지 못한다(2026-08-17
		// ExitPlanCancelFillConcurrencyIntegrationTest에서 재현 — "정확히 한 번" 규칙 위반). entityManager.clear()로
		// 세션 전체를 비우면 이 메서드가 같은 트랜잭션을 공유하는 다른 호출자(OSIV 없는 이 앱에서도 테스트의
		// @Transactional처럼 더 넓은 트랜잭션 안에서 호출될 수 있다)가 이미 들고 있는 무관한 엔티티까지 분리돼
		// 500으로 이어질 수 있어(재현 확인), 이 메서드가 직접 로딩한 두 엔티티만 선택적으로 detach한다.
		Holding preloadedHolding = ownershipCheck.getHolding();
		Long accountId = preloadedHolding.getAccount().getId();
		Instrument instrumentRef = ownershipCheck.getInstrument();
		// detach 전에 반드시 flush한다 — 같은 트랜잭션 안에서 이미 이 두 엔티티에 가해진(예: 테스트의
		// @Transactional처럼 더 넓은 트랜잭션을 공유할 때 앞서 호출된 생성 로직의 reserveQuantity) 아직 flush되지
		// 않은 변경을 detach가 그대로 버리면, 아래 재조회가 DB의 예전 값을 읽어 "예약된 수량보다 큰 수량을 해제"
		// 같은 원장 불일치를 낸다(재현 확인).
		entityManager.flush();
		entityManager.detach(ownershipCheck);
		entityManager.detach(preloadedHolding);

		// 잠금 순서 account → holding → plan — 기존 시장가·지정가 매도와 동일하게 현금 갱신 전에 계좌를 먼저
		// 잠근다(PR #349 리뷰 차단 수정).
		Account account = accountService.getAccountByIdForUpdate(accountId);
		Holding holding = portfolioSellService.getHoldingForUpdate(account, instrumentRef);

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
		Order order = executeMarketSell(plan, account, holding, currentPrice, now);

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
	// 동일한 fee 계산(코인 전용, LimitOrderFeeCalculator 재사용)·lot 배분·실현손익 반영을 공유한다. account는
	// 호출부가 이미 잠근 것을 그대로 받는다(holding.getAccount() lazy 참조를 다시 쓰지 않는다).
	private Order executeMarketSell(
		ExitPlan plan, Account account, Holding holding, BigDecimal currentPrice, LocalDateTime now) {
		Instrument instrument = plan.getInstrument();
		BigDecimal quantity = plan.getQuantity();

		LimitOrderFeeCalculator.Reservation reservation = LimitOrderFeeCalculator.calculate(quantity, currentPrice);
		long amount = reservation.amount();
		long fee = reservation.fee();

		String idempotencyKey = triggerIdempotencyKey(plan.getId());
		// Order.create·createForPracticeAttempt 둘 다 MARKET 주문을 즉시 FILLED로 만든다
		// (OrderExecutionService와 동일) — 별도 markFilled 호출이 필요 없다.
		//
		// **튜토리얼 자동 예약이 발동한 매도는 반드시 attempt·실행 세대에 귀속시킨다**(042 EXITPRESET-013).
		// 042의 판정이 전부 `orders.practice_attempt_id`로 실행 세대를 좁히기 때문이다 — 귀속 없이 만들면
		// 이 매도가 원장에서 통째로 빠져 (1) 순보유수량이 매수분 그대로 남아 프리셋이 영구 잠기고
		// (2) 재매수에 새 기준선·새 예약이 생기지 않고 (3) 매도 원인이 항상 null/MANUAL이 되고
		// (4) 재시작이 순체결수량과 holding 잔량 불일치로 영구히 409가 된다. 043의 attempt 주문 목록에도
		// 나타나지 않는다.
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
