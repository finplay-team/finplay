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
		verifyNoInteractions(tutorialAccountService);
	}

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
		verify(tutorialAccountService)
			.resetForUpdate(USER_ID, Market.CRYPTO, NOW);
	}

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
