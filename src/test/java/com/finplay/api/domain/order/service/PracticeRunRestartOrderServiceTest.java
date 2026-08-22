// attempt 실행 세대 재시작 시 예약 취소, 순체결 집계와 보상 매도 원장 생성을 검증한다.
package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.OrderExecutionPriceDto;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.market.service.PriceQuoteDto;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.domain.portfolio.service.SellAllocationDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeRunRestartOrderServiceTest {

	private static final Long USER_ID = 7L;
	private static final Long ATTEMPT_ID = 11L;
	private static final Long INSTRUMENT_ID = 21L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 15, 0);

	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final TradeRepository tradeRepository = mock(TradeRepository.class);
	private final AccountService accountService = mock(AccountService.class);
	private final TutorialAccountService tutorialAccountService = mock(TutorialAccountService.class);
	private final InstrumentService instrumentService = mock(InstrumentService.class);
	private final PriceQueryService priceQueryService = mock(PriceQueryService.class);
	private final PortfolioSellService portfolioSellService = mock(PortfolioSellService.class);
	private final PracticeOrderSettlementService practiceOrderSettlementService = mock(
		PracticeOrderSettlementService.class);
	private final PracticeRunRestartOrderService service = new PracticeRunRestartOrderService(
		orderRepository, tradeRepository, accountService, tutorialAccountService, instrumentService,
		portfolioSellService, practiceOrderSettlementService);

	@Test
	void cleanupCurrentRunCancelsPendingBuyAndSellAndReturnsReservationsExactlyOnce() {
		Fixture fixture = fixture();
		// PR #452 리뷰 차단 1번: validateInstrument가 이 메서드 도달 전 isTutorialSample()을 강제하므로,
		// PENDING 지정가 매수 예약은 실제 Account가 아니라 튜토리얼 계좌에 걸려 있어야 한다.
		when(tutorialAccountService.getOrCreateForUpdate(
			USER_ID, Market.CRYPTO, NOW))
			.thenReturn(fixture.tutorialAccount());
		fixture.tutorialAccount().reserveCash(100_050L);
		fixture.holding().applyBuy(BigDecimal.ONE, BigDecimal.valueOf(900_000), NOW.minusHours(1));
		fixture.holding().reserveQuantity(BigDecimal.ONE);
		Order pendingBuy = attributedPendingOrder(
			fixture, OrderSide.BUY, new BigDecimal("0.1"), new BigDecimal("1000000"), "pending-buy");
		Order pendingSell = attributedPendingOrder(
			fixture, OrderSide.SELL, BigDecimal.ONE, BigDecimal.valueOf(1_000_000), "pending-sell");
		stubRun(fixture, List.of(pendingBuy, pendingSell), List.of());

		service.cleanupCurrentRun(command());
		service.cleanupCurrentRun(command());

		assertThat(pendingBuy.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(pendingSell.getStatus()).isEqualTo(OrderStatus.CANCELLED);
		assertThat(fixture.tutorialAccount().getReservedCash()).isZero();
		assertThat(fixture.account().getReservedCash()).isZero();
		assertThat(fixture.holding().getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		verifyNoInteractions(priceQueryService);
		// TUTORIAL-CASH-ISOL-006: 순체결수량 0 즉시 반환 경로도 재시작마다 튜토리얼 계좌를 리셋해야 한다.
		verify(tutorialAccountService, times(2))
			.resetForUpdate(USER_ID, Market.CRYPTO, NOW);
	}

	@Test
	void cleanupCurrentRunAggregatesFilledBuyMinusPartialSellAndCreatesAuditableCompensatingSell() {
		Fixture fixture = fixture();
		fixture.holding().applyBuy(new BigDecimal("1.5"), BigDecimal.valueOf(90_000), NOW.minusHours(1));
		Trade buy = filledTrade(OrderSide.BUY, "2.0");
		Trade partialSell = filledTrade(OrderSide.SELL, "0.5");
		stubRun(fixture, List.of(), List.of(buy, partialSell));
		when(priceQueryService.getOrderExecutionPrice(fixture.instrument()))
			.thenReturn(executionPrice("100000"));
		SellAllocationDto allocation = new SellAllocationDto(135_000L, 10L);
		when(portfolioSellService.applySellTrade(
			eq(fixture.holding()), any(Trade.class), eq(new BigDecimal("1.5")), eq(NOW)))
			.thenReturn(allocation);

		service.cleanupCurrentRun(command());

		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(orderRepository).save(orderCaptor.capture());
		verify(tradeRepository).save(tradeCaptor.capture());
		Order auditOrder = orderCaptor.getValue();
		Trade auditTrade = tradeCaptor.getValue();
		assertThat(auditOrder.getSide()).isEqualTo(OrderSide.SELL);
		assertThat(auditOrder.getPracticeAttemptId()).isEqualTo(ATTEMPT_ID);
		assertThat(auditOrder.getPracticeAttemptRunNumber()).isEqualTo(1L);
		assertThat(auditOrder.getIdempotencyKey()).isEqualTo("practice-restart:11:1");
		assertThat(auditTrade.getQuantity()).isEqualByComparingTo("1.5");
		assertThat(auditTrade.getPrice()).isEqualByComparingTo("100000");
		assertThat(auditTrade.getAmount()).isEqualTo(150_000L);
		assertThat(auditTrade.getFee()).isEqualTo(75L);
		verify(portfolioSellService).applySellTrade(fixture.holding(), auditTrade, new BigDecimal("1.5"), NOW);
		verify(portfolioSellService).finalizeSellRealizedPnl(
			fixture.account(), auditTrade, 150_000L, 75L, allocation, NOW);
		// TUTORIAL-CASH-ISOL-006: 보상매도가 튜토리얼 계좌에 반영된 뒤(finalizeSellRealizedPnl) 리셋이
		// 마지막에 호출돼야 한다 — 순서가 바뀌면 보상매도 증가분이 리셋 이후에 남아 초기값(1000만원)을 넘어선다.
		verify(tutorialAccountService)
			.resetForUpdate(USER_ID, Market.CRYPTO, NOW);
		InOrder order = inOrder(portfolioSellService, tutorialAccountService);
		order.verify(portfolioSellService).finalizeSellRealizedPnl(
			fixture.account(), auditTrade, 150_000L, 75L, allocation, NOW);
		order.verify(tutorialAccountService)
			.resetForUpdate(USER_ID, Market.CRYPTO, NOW);
	}

	@Test
	void cleanupCurrentRunCreatesNoCompensationWhenFilledBuyAndSellAreEqual() {
		Fixture fixture = fixture();
		stubRun(fixture, List.of(), List.of(filledTrade(OrderSide.BUY, "2"), filledTrade(OrderSide.SELL, "2")));

		service.cleanupCurrentRun(command());

		verify(orderRepository, never()).save(any());
		verify(tradeRepository, never()).save(any());
		verifyNoInteractions(priceQueryService, portfolioSellService);
		// TUTORIAL-CASH-ISOL-006: 보상매도가 없어도(순체결수량 0) 리셋은 여전히 일어나야 한다.
		verify(tutorialAccountService)
			.resetForUpdate(USER_ID, Market.CRYPTO, NOW);
	}

	@Test
	void cleanupCurrentRunRejectsWhenAvailableHoldingDoesNotExactlyMatchNetFilledQuantity() {
		Fixture fixture = fixture();
		fixture.holding().applyBuy(BigDecimal.ONE, BigDecimal.valueOf(90_000), NOW.minusHours(1));
		stubRun(fixture, List.of(), List.of(filledTrade(OrderSide.BUY, "1.5")));

		assertThatThrownBy(() -> service.cleanupCurrentRun(command()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		verify(orderRepository, never()).save(any());
		verify(tradeRepository, never()).save(any());
		verifyNoInteractions(priceQueryService);
		// TUTORIAL-CASH-ISOL-006: 정리 자체가 실패(BusinessException)하면 재시작이 완료된 게 아니므로
		// 튜토리얼 계좌 리셋도 일어나지 않아야 한다.
		verifyNoInteractions(tutorialAccountService);
	}

	// 실제 종목이 정리 대상으로 넘어오면 계좌·holding에 손대기 전에 막는다 — 이 방어선이 이슈 #433 수정의 전제다.
	@Test
	void cleanupCurrentRunRejectsRealInstrumentBeforeTouchingAccountOrHolding() {
		Fixture fixture = fixture();
		ReflectionTestUtils.setField(fixture.instrument(), "tutorialSample", false);
		when(orderRepository.findPracticeRunOrdersForUpdate(ATTEMPT_ID, 1L)).thenReturn(List.of());
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(fixture.instrument());

		assertThatThrownBy(() -> service.cleanupCurrentRun(command()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		verify(orderRepository, never()).save(any());
		verifyNoInteractions(tradeRepository, accountService, portfolioSellService, priceQueryService,
			tutorialAccountService);
	}

	@Test
	void cleanupCurrentRunWithoutInstrumentAllowsOnlyEmptyOrderSet() {
		when(orderRepository.findPracticeRunOrdersForUpdate(ATTEMPT_ID, 1L)).thenReturn(List.of());
		PracticeRunRestartCommand command = new PracticeRunRestartCommand(
			ATTEMPT_ID, 1L, USER_ID, Market.CRYPTO, null, null, NOW);

		service.cleanupCurrentRun(command);

		verifyNoInteractions(tradeRepository, accountService, instrumentService, priceQueryService,
			portfolioSellService);
		// TUTORIAL-CASH-ISOL-006: 종목 미선택(instrumentId == null) 즉시 반환 경로도 리셋 대상이다.
		verify(tutorialAccountService)
			.resetForUpdate(USER_ID, Market.CRYPTO, NOW);
	}

	// 이슈 #440: instrumentId == null인데 현재 attempt·run에 귀속된 주문이 남아 있으면 409로 막는다.
	// PR #434가 legacy 실제 종목 재시작을 허용한 뒤로 이 분기가 유일한 안전망이 됐는데, 위
	// cleanupCurrentRunWithoutInstrumentAllowsOnlyEmptyOrderSet은 주문이 비어 있는 경우만 다뤄
	// "주문이 남아 있으면 막힌다" 쪽이 비어 있었다.
	@Test
	void cleanupCurrentRunWithoutInstrumentRejectsWhenOrdersExist() {
		Fixture fixture = fixture();
		Order leftover = attributedPendingOrder(
			fixture, OrderSide.BUY, BigDecimal.ONE, new BigDecimal("70000000"), "leftover");
		when(orderRepository.findPracticeRunOrdersForUpdate(ATTEMPT_ID, 1L)).thenReturn(List.of(leftover));
		PracticeRunRestartCommand command = new PracticeRunRestartCommand(
			ATTEMPT_ID, 1L, USER_ID, Market.CRYPTO, null, null, NOW);

		assertThatThrownBy(() -> service.cleanupCurrentRun(command))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		// 막힌 뒤에는 계좌·holding·원장 어느 쪽도 건드리지 않고, 튜토리얼 계좌 리셋도 일어나지 않아야 한다.
		assertThat(leftover.getStatus()).isEqualTo(OrderStatus.PENDING);
		verify(orderRepository, never()).save(any());
		verifyNoInteractions(tradeRepository, accountService, instrumentService, priceQueryService,
			portfolioSellService, tutorialAccountService);
	}

	private void stubRun(Fixture fixture, List<Order> orders, List<Trade> trades) {
		when(orderRepository.findPracticeRunOrdersForUpdate(ATTEMPT_ID, 1L)).thenReturn(orders);
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(fixture.instrument());
		when(accountService.getAccountForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(fixture.account());
		when(tradeRepository.findFilledPracticeRunTrades(ATTEMPT_ID, 1L)).thenReturn(trades);
		when(portfolioSellService.getHoldingForUpdate(fixture.account(), fixture.instrument()))
			.thenReturn(fixture.holding());
	}

	private static PracticeRunRestartCommand command() {
		return new PracticeRunRestartCommand(
			ATTEMPT_ID, 1L, USER_ID, Market.CRYPTO, INSTRUMENT_ID, new BigDecimal("100000"), NOW);
	}

	private static Fixture fixture() {
		User user = User.create("restart@finplay.com", "hash", "restart", NOW);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		Account account = Account.create(user, Market.CRYPTO, NOW);
		ReflectionTestUtils.setField(account, "id", 31L);
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Holding holding = Holding.create(account, instrument, NOW);
		ReflectionTestUtils.setField(holding, "id", 41L);
		TutorialAccount tutorialAccount = TutorialAccount.create(
			user, Market.CRYPTO, NOW);
		return new Fixture(account, instrument, holding, tutorialAccount);
	}

	private static Order attributedPendingOrder(
		Fixture fixture, OrderSide side, BigDecimal quantity, BigDecimal limitPrice, String key) {
		return Order.createLimitPendingForPracticeAttempt(
			fixture.account().getUser(), fixture.account(), fixture.instrument(), side, quantity, limitPrice,
			ATTEMPT_ID, 1L, key, "a".repeat(64), NOW);
	}

	private static Trade filledTrade(OrderSide side, String quantity) {
		Trade trade = mock(Trade.class);
		when(trade.getSide()).thenReturn(side);
		when(trade.getQuantity()).thenReturn(new BigDecimal(quantity));
		return trade;
	}

	private static OrderExecutionPriceDto executionPrice(String price) {
		return new OrderExecutionPriceDto(
			new PriceQuoteDto(new BigDecimal(price), NOW, PriceStatus.AVAILABLE, null), null);
	}

	private record Fixture(Account account, Instrument instrument, Holding holding, TutorialAccount tutorialAccount) {
	}
}
