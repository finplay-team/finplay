// 지정가 주문을 체결하며 attempt 귀속 주문은 attempt → order → account → holding 순서로 잠그는 서비스
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
import java.util.List;
import java.util.Optional;
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

	// plan.md "체결 리스너·체결 서비스" — order(자기 자신) → account → (SELL만) holding 순으로 잠근다.
	// 이미 order를 락으로 잡은 뒤 PENDING 여부를 재확인하므로 같은 주문에 이벤트가 중복 도착해도 두 번째 호출은 no-op.
	// order.limit-fill-executor.enabled=false(폴백 경로)와, 실행기 큐 청크(fillBatch) 없이 주문 1건만
	// 단독으로 체결해야 하는 호출부가 쓴다.
	// ADR-0028 — holdings 신규 생성 INSERT 데드락 완화를 위해 READ COMMITTED로 좁혀 적용한다(계좌·보유
	// 정합성은 명시적 FOR UPDATE 락에 의존하므로 영향 없음).
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void fillIfPending(Long orderId) {
		fillOnePending(orderId, LocalDateTime.now(clock));
	}

	// attempt/run 정산(PracticeOrderSettlementService.settleCurrentRun)이 재시작·복기 등 과거 시각으로 재현할
	// 체결가·시각을 명시해야 할 때 쓴다 — canonical 가격은 이 pricedAt 기준으로 계산된다.
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void fillIfPending(Long orderId, LocalDateTime pricedAt) {
		fillOnePending(orderId, pricedAt);
	}

	// ADR-0025 — 파티션 워커가 청크 하나(최대 order.limit-fill-executor.batch-size건)를 트랜잭션 1개로
	// 처리한다. 청크 안의 한 건이 예외를 던지면 이 메서드 전체가 롤백된다 — 이미 처리된 앞선 건도 함께
	// 되돌아가 PENDING으로 남고, 다음 가격 틱이 findPendingLimitOrdersToFill로 다시 후보에 올린다(ADR-0024
	// §결정 2와 같은 재시도 철학). "청크 안에서 건별로 flush 후 catch"처럼 실패한 건만 골라내 나머지를
	// 그대로 커밋하는 방식은 택하지 않았다 — Hibernate는 flush 중 예외가 나면 그 세션을 더 이상 신뢰할 수
	// 없다고 보므로, 예외 이후에도 같은 영속성 컨텍스트로 나머지 건을 계속 처리하는 건 검증되지 않은 위험을
	// 감수하는 것이다(ADR-0025 §결정 3의 선택 이유).
	// ADR-0028 — holdings 신규 생성 INSERT 데드락 완화를 위해 READ COMMITTED로 좁혀 적용한다.
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void fillBatch(List<Long> orderIds) {
		LocalDateTime pricedAt = LocalDateTime.now(clock);
		for (Long orderId : orderIds) {
			fillOnePending(orderId, pricedAt);
		}
	}

	// attempt 귀속은 비잠금 preflight로 scalar만 읽고 attempt를 먼저 잠근다. restart가 attempt 잠금을 잡은 채
	// 주문을 취소했다면 대기 후 order FOR UPDATE의 PENDING 재확인에서 no-op 되므로 과거 run을 체결하지 않는다.
	// 일반 주문은 preflight가 비어 기존 order → account → holding 잠금 순서를 그대로 사용한다.
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

		// 샌드박스(튜토리얼) 종목 지정가 매수 체결은 실제 Account 대신 같은 사용자·시장의 튜토리얼 계좌
		// 현금을 예약해제·차감(또는 확정)한다(047 TUTORIAL-CASH-ISOL-002, plan.md "호출부 변경 지점" 4번).
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

		// 기존 시장가 매수와 동일한 lot 생성 로직 재사용 — holding이 없으면(신규 종목 첫 매수) 여기서 새로 만든다.
		portfolioBuyService.applyBuyTrade(account, instrument, trade, quantity, executionPrice, fee, now);

		order.markFilled();
		practiceOrderAttributionPort.createRiskSnapshotOnBuyFill(order, trade, now);
	}

	private void fillSell(
		Order order, Account account, BigDecimal quantity, BigDecimal executionPrice, long amount, long fee,
		LocalDateTime now) {
		Instrument instrument = order.getInstrument();

		// SELL은 여기서 직접 holding을 잠근다(잠금 순서 account → holding). BUY도 이제 holding을 잠근다 —
		// applyBuyTrade(PortfolioBuyService)가 내부에서 findByAccountIdAndInstrumentIdForUpdate로 잠근다(이슈 #224).
		Holding holding = portfolioSellService.getHoldingForUpdate(account, instrument);
		holding.releaseReservedQuantity(quantity);

		Trade trade = Trade.of(
			order, account, instrument, null, order.getSide(), executionPrice, quantity, amount, fee, null, now, now);
		tradeRepository.save(trade);

		// 기존 FIFO lot 소비 재사용 — releaseReservedQuantity(예약 해제)와 applySell(실보유 차감)을 함께 호출한다.
		SellAllocationDto allocation = portfolioSellService.applySellTrade(holding, trade, quantity, now);

		// 기존 시장가 매도(OrderExecutionService)와 동일한 실현손익 공식·반영을 공유 메서드로 재사용한다.
		portfolioSellService.finalizeSellRealizedPnl(account, trade, amount, fee, allocation, now);

		order.markFilled();
		// 커밋 이후(after-commit)에만 랭킹에 반영되도록 이벤트만 발행한다 — 기존 시장가 매도와 동일 훅 재사용.
		eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));
	}

	private boolean isTriggered(Order order, BigDecimal canonicalPrice) {
		return order.getSide() == OrderSide.BUY
			? order.getLimitPrice().compareTo(canonicalPrice) >= 0
			: order.getLimitPrice().compareTo(canonicalPrice) <= 0;
	}
}
