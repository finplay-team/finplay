// ExitPlanFillService.fillIfPending의 트리거 방향 판정·잠금 순서·정확히 한 번 규칙·반대 조건 자동 취소를 검증하는 단위 테스트다.
package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanCondition;
import com.finplay.api.domain.order.entity.ExitPlanConditionStatus;
import com.finplay.api.domain.order.entity.ExitPlanConditionType;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.ExitPriceType;
import com.finplay.api.domain.order.entity.Order;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class ExitPlanFillServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-14T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
	private static final Long PLAN_ID = 700L;
	private static final BigDecimal STOP_LOSS_PRICE = new BigDecimal("95000");
	private static final BigDecimal TAKE_PROFIT_PRICE = new BigDecimal("110000");

	private final ExitPlanRepository exitPlanRepository = mock(ExitPlanRepository.class);
	private final ExitPlanConditionRepository exitPlanConditionRepository = mock(ExitPlanConditionRepository.class);
	private final AccountService accountService = mock(AccountService.class);
	private final PortfolioSellService portfolioSellService = mock(PortfolioSellService.class);
	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final TradeRepository tradeRepository = mock(TradeRepository.class);
	private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
	private final EntityManager entityManager = mock(EntityManager.class);

	private final ExitPlanFillService service = new ExitPlanFillService(
		exitPlanRepository, exitPlanConditionRepository, accountService, portfolioSellService, orderRepository,
		tradeRepository, clock, eventPublisher, entityManager);

	private Holding holding;
	private ExitPlan plan;
	private ExitPlanCondition stopLossCondition;
	private ExitPlanCondition takeProfitCondition;

	@BeforeEach
	void setUp() {
		holding = holdingWithReservation();
		plan = pendingPlan(holding);
		stopLossCondition = ExitPlanCondition.create(plan, ExitPlanConditionType.STOP_LOSS, STOP_LOSS_PRICE, NOW);
		takeProfitCondition = ExitPlanCondition.create(plan, ExitPlanConditionType.TAKE_PROFIT, TAKE_PROFIT_PRICE, NOW);

		when(exitPlanRepository.findById(PLAN_ID)).thenReturn(Optional.of(plan));
		when(accountService.getAccountByIdForUpdate(holding.getAccount().getId())).thenReturn(holding.getAccount());
		when(portfolioSellService.getHoldingForUpdate(holding.getAccount(), plan.getInstrument())).thenReturn(holding);
		when(exitPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
		when(exitPlanConditionRepository.findByExitPlanIdOrderByIdAsc(PLAN_ID))
			.thenReturn(List.of(stopLossCondition, takeProfitCondition));
		when(portfolioSellService.applySellTrade(any(), any(), any(), any()))
			.thenReturn(new SellAllocationDto(0L, 0L));
	}

	@Test
	@DisplayName("currentPrice가 익절가 이상이면 FILLED_TAKE_PROFIT으로 전이하고 반대(STOP_LOSS) 조건을 자동 취소한다")
	void fillIfPendingFillsTakeProfitAndCancelsOppositeConditionWhenPriceMeetsOrExceedsTakeProfit() {
		service.fillIfPending(PLAN_ID, TAKE_PROFIT_PRICE);

		assertThat(plan.getStatus()).isEqualTo(ExitPlanStatus.FILLED_TAKE_PROFIT);
		assertThat(plan.getTriggeredOrder()).isNotNull();
		assertThat(plan.getClosedAt()).isEqualTo(NOW);
		assertThat(takeProfitCondition.getStatus()).isEqualTo(ExitPlanConditionStatus.TRIGGERED);
		assertThat(stopLossCondition.getStatus()).isEqualTo(ExitPlanConditionStatus.CANCELLED_BY_OCO);
	}

	@Test
	void fillIfPendingDoesNotPublishRealizedPnlUpdatedEventWhenInstrumentIsTutorialSample() {
		// 이슈 #549 — 튜토리얼 샘플 종목의 OCO 자동 매도는 실제 매도 이력 없는 계좌를 랭킹에 올리게 되므로 이벤트를 발행하지 않는다.
		ReflectionTestUtils.setField(plan.getInstrument(), "tutorialSample", true);

		service.fillIfPending(PLAN_ID, TAKE_PROFIT_PRICE);

		assertThat(plan.getStatus()).isEqualTo(ExitPlanStatus.FILLED_TAKE_PROFIT);
		verifyNoInteractions(eventPublisher);
	}

	@Test
	@DisplayName("currentPrice가 손절가 이하이면 FILLED_STOP_LOSS로 전이하고 반대(TAKE_PROFIT) 조건을 자동 취소한다")
	void fillIfPendingFillsStopLossAndCancelsOppositeConditionWhenPriceMeetsOrFallsBelowStopLoss() {
		service.fillIfPending(PLAN_ID, STOP_LOSS_PRICE);

		assertThat(plan.getStatus()).isEqualTo(ExitPlanStatus.FILLED_STOP_LOSS);
		assertThat(plan.getTriggeredOrder()).isNotNull();
		assertThat(stopLossCondition.getStatus()).isEqualTo(ExitPlanConditionStatus.TRIGGERED);
		assertThat(takeProfitCondition.getStatus()).isEqualTo(ExitPlanConditionStatus.CANCELLED_BY_OCO);
	}

	@Test
	@DisplayName("account → holding → plan 순서로 잠근다(리뷰 지적 수정) — account를 가장 먼저 잠근다")
	void fillIfPendingLocksAccountBeforeHoldingBeforePlan() {
		service.fillIfPending(PLAN_ID, TAKE_PROFIT_PRICE);

		var inOrder = org.mockito.Mockito.inOrder(accountService, portfolioSellService, exitPlanRepository);
		inOrder.verify(accountService).getAccountByIdForUpdate(holding.getAccount().getId());
		inOrder.verify(portfolioSellService).getHoldingForUpdate(holding.getAccount(), plan.getInstrument());
		inOrder.verify(exitPlanRepository).findByIdForUpdate(PLAN_ID);
	}

	@Test
	@DisplayName("plan이 이미 PENDING이 아니면(먼저 종결됨) no-op으로 skip하고 아무 것도 체결·변경하지 않는다 — 최초 커밋만 승자")
	void fillIfPendingIsNoOpWhenPlanIsAlreadyTerminal() {
		plan.cancel(NOW.minusMinutes(1));

		service.fillIfPending(PLAN_ID, TAKE_PROFIT_PRICE);

		assertThat(plan.getStatus()).isEqualTo(ExitPlanStatus.CANCELLED);
		assertThat(stopLossCondition.getStatus()).isEqualTo(ExitPlanConditionStatus.PENDING);
		assertThat(takeProfitCondition.getStatus()).isEqualTo(ExitPlanConditionStatus.PENDING);
		verify(orderRepository, never()).save(any());
		verify(tradeRepository, never()).save(any());
		verifyNoInteractions(eventPublisher);
	}

	@Test
	@DisplayName("plan이 존재하지 않으면(exitPlanRepository.findById가 empty) no-op으로 조용히 종료한다")
	void fillIfPendingIsNoOpWhenPlanDoesNotExist() {
		when(exitPlanRepository.findById(PLAN_ID)).thenReturn(Optional.empty());

		service.fillIfPending(PLAN_ID, TAKE_PROFIT_PRICE);

		verifyNoInteractions(accountService, portfolioSellService, orderRepository, tradeRepository, eventPublisher);
	}

	@Test
	@DisplayName("잠근 plan을 확정 가격선으로 재판정해 두 방향 모두 미충족이면 no-op이다(방어적 재판정)")
	void fillIfPendingIsNoOpWhenLockedPlanPriceDoesNotActuallyMeetEitherCondition() {
		BigDecimal priceBetweenStopLossAndTakeProfit = new BigDecimal("100000");

		service.fillIfPending(PLAN_ID, priceBetweenStopLossAndTakeProfit);

		assertThat(plan.getStatus()).isEqualTo(ExitPlanStatus.PENDING);
		verify(orderRepository, never()).save(any());
		verifyNoInteractions(eventPublisher);
	}

	@Test
	@DisplayName("체결 시 holding 예약이 정확히 소비되고(releaseReservedQuantity) triggeredOrder가 저장된다")
	void fillIfPendingReleasesReservedQuantityAndPersistsTriggeredOrder() {
		BigDecimal quantityBefore = holding.getReservedQuantity();

		service.fillIfPending(PLAN_ID, TAKE_PROFIT_PRICE);

		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(quantityBefore.subtract(plan.getQuantity()));
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(1)).save(orderCaptor.capture());
		assertThat(plan.getTriggeredOrder()).isEqualTo(orderCaptor.getValue());
		verify(tradeRepository, times(1)).save(any(Trade.class));
	}

	private static Holding holdingWithReservation() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 1L);
		Account account = Account.create(owner(), Market.CRYPTO, NOW);
		ReflectionTestUtils.setField(account, "id", 10L);
		Holding holding = Holding.create(account, instrument, NOW);
		ReflectionTestUtils.setField(holding, "id", 100L);
		holding.applyBuy(new BigDecimal("10"), new BigDecimal("100000"), NOW);
		holding.reserveQuantity(new BigDecimal("1"));
		return holding;
	}

	private static ExitPlan pendingPlan(Holding holding) {
		ExitPlan plan = ExitPlan.createGeneral(
			owner(), holding, holding.getInstrument(), new BigDecimal("1"), holding.getAveragePrice(),
			ExitPriceType.PRICE, null, null, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE,
			new BigDecimal("100500"), NOW, "h".repeat(64), NOW);
		ReflectionTestUtils.setField(plan, "id", PLAN_ID);
		return plan;
	}

	private static User owner() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		ReflectionTestUtils.setField(user, "id", 1L);
		return user;
	}
}
