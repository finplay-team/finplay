package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class LimitOrderFillServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final TradeRepository tradeRepository = mock(TradeRepository.class);
	private final AccountService accountService = mock(AccountService.class);
	private final TutorialAccountService tutorialAccountService = mock(TutorialAccountService.class);
	private final PortfolioBuyService portfolioBuyService = mock(PortfolioBuyService.class);
	private final PortfolioSellService portfolioSellService = mock(PortfolioSellService.class);
	private final PracticeOrderAttributionPort practiceOrderAttributionPort = mock(
		PracticeOrderAttributionPort.class);
	private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

	private final LimitOrderFillService service = new LimitOrderFillService(
		orderRepository, tradeRepository, accountService, tutorialAccountService, portfolioBuyService,
		portfolioSellService, practiceOrderAttributionPort, clock, eventPublisher);

	@Test
	void fillIfPendingFillsBuyOrderConfirmsReservedCashAndAppliesBuyTrade() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L - 100_050L);

		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		Trade savedTrade = tradeCaptor.getValue();
		assertThat(savedTrade.getPrice()).isEqualByComparingTo("1000000");
		assertThat(savedTrade.getAmount()).isEqualTo(100_000L);
		assertThat(savedTrade.getFee()).isEqualTo(50L);
		assertThat(savedTrade.getRealizedPnl()).isNull();

		verify(portfolioBuyService).applyBuyTrade(
			account, instrument, savedTrade, new BigDecimal("0.1"), new BigDecimal("1000000"), 50L, NOW);
		verifyNoInteractions(portfolioSellService);
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void fillIfPendingLocksAttemptBeforeAttributedOrder() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = attributedLimitPendingBuyOrder(account, instrument);
		PracticeOrderFillAttributionDto attribution = new PracticeOrderFillAttributionDto(
			20L, 1L, 30L, instrument.getId());
		when(orderRepository.findPracticeFillAttribution(order.getId())).thenReturn(Optional.of(attribution));
		when(practiceOrderAttributionPort.lockForFill(attribution, NOW))
			.thenReturn(new PracticeOrderFillContextDto(true, new BigDecimal("900000")));
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());

		InOrder lockOrder = org.mockito.Mockito.inOrder(orderRepository, practiceOrderAttributionPort);
		lockOrder.verify(orderRepository).findPracticeFillAttribution(order.getId());
		lockOrder.verify(practiceOrderAttributionPort).lockForFill(attribution, NOW);
		lockOrder.verify(orderRepository).findByIdForUpdate(order.getId());
		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		assertThat(tradeCaptor.getValue().getPrice()).isEqualByComparingTo("900000");
		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L - 90_045L);
	}

	@Test
	void fillIfPendingSkipsAttemptLockForOrdinaryOrder() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findPracticeFillAttribution(order.getId())).thenReturn(Optional.empty());
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());

		verify(practiceOrderAttributionPort, never()).lockForFill(any(), any());
	}

	@Test
	void fillIfPendingRejectsAttributedOrderFromStaleRunAfterOrderLock() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = attributedLimitPendingBuyOrder(account, instrument);
		PracticeOrderFillAttributionDto attribution = new PracticeOrderFillAttributionDto(
			20L, 1L, 30L, instrument.getId());
		when(orderRepository.findPracticeFillAttribution(order.getId())).thenReturn(Optional.of(attribution));
		when(practiceOrderAttributionPort.lockForFill(attribution, NOW))
			.thenReturn(new PracticeOrderFillContextDto(false, null));
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

		assertThatThrownBy(() -> service.fillIfPending(order.getId()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_STEP_LOCKED));

		verifyNoInteractions(accountService, tradeRepository, portfolioBuyService, portfolioSellService);
	}

	@Test
	void fillIfPendingBuyConfirmsReservedCashInTutorialAccountOnlyWhenInstrumentIsTutorialSample() {
		Instrument instrument = cryptoInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account();
		TutorialAccount tutorialAccount = tutorialAccount();
		tutorialAccount.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);
		when(tutorialAccountService.getOrCreateForUpdate(any(), eq(Market.CRYPTO), eq(NOW)))
			.thenReturn(tutorialAccount);

		service.fillIfPending(order.getId());

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(tutorialAccount.getReservedCash()).isZero();
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L - 100_050L);
		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void fillIfPendingBuyForAttributedTutorialSampleReleasesAndDeductsInTutorialAccountOnly() {
		Instrument instrument = cryptoInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account();
		TutorialAccount tutorialAccount = tutorialAccount();
		tutorialAccount.reserveCash(100_050L);
		Order order = attributedLimitPendingBuyOrder(account, instrument);
		PracticeOrderFillAttributionDto attribution = new PracticeOrderFillAttributionDto(
			20L, 1L, 30L, instrument.getId());
		when(orderRepository.findPracticeFillAttribution(order.getId())).thenReturn(Optional.of(attribution));
		when(practiceOrderAttributionPort.lockForFill(attribution, NOW))
			.thenReturn(new PracticeOrderFillContextDto(true, new BigDecimal("900000")));
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);
		when(tutorialAccountService.getOrCreateForUpdate(any(), eq(Market.CRYPTO), eq(NOW)))
			.thenReturn(tutorialAccount);

		service.fillIfPending(order.getId());

		assertThat(tutorialAccount.getReservedCash()).isZero();
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L - 90_045L);
		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void fillIfPendingBuyDoesNotTouchTutorialAccountWhenInstrumentIsReal() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());

		verifyNoInteractions(tutorialAccountService);
	}

	@Test
	void fillIfPendingFillsBuyOrderWhenNoExistingHoldingForNewInstrument() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());

		verify(portfolioBuyService).applyBuyTrade(
			eq(account), eq(instrument), any(Trade.class), eq(new BigDecimal("0.1")), eq(new BigDecimal("1000000")),
			eq(50L), eq(NOW));
		verifyNoInteractions(portfolioSellService);
	}

	@Test
	void fillIfPendingFillsSellOrderReleasesReservedQuantityAndAppliesRealizedPnl() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Holding holding = Holding.create(account, instrument, NOW.minusDays(1));
		holding.applyBuy(new BigDecimal("1"), new BigDecimal("900000"), NOW.minusDays(1));
		holding.reserveQuantity(new BigDecimal("0.1"));
		Order order = limitPendingOrder(account, instrument, OrderSide.SELL, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);
		when(portfolioSellService.getHoldingForUpdate(account, instrument)).thenReturn(holding);
		SellAllocationDto allocation = new SellAllocationDto(90_000L, 40L);
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(new BigDecimal("0.1")), eq(NOW)))
			.thenReturn(allocation);
		when(portfolioSellService.finalizeSellRealizedPnl(eq(account), any(Trade.class), eq(100_000L), eq(50L),
			eq(allocation), eq(NOW))).thenReturn(9_910L);

		service.fillIfPending(order.getId());

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);

		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		Trade savedTrade = tradeCaptor.getValue();
		assertThat(savedTrade.getPrice()).isEqualByComparingTo("1000000");
		assertThat(savedTrade.getAmount()).isEqualTo(100_000L);
		assertThat(savedTrade.getFee()).isEqualTo(50L);

		verify(portfolioSellService).applySellTrade(holding, savedTrade, new BigDecimal("0.1"), NOW);
		verify(portfolioSellService).finalizeSellRealizedPnl(account, savedTrade, 100_000L, 50L, allocation, NOW);
		ArgumentCaptor<RealizedPnlUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(RealizedPnlUpdatedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().accountId()).isEqualTo(account.getId());
		verifyNoInteractions(portfolioBuyService);
	}

	@Test
	void fillIfPendingSellDoesNotPublishRealizedPnlUpdatedEventWhenInstrumentIsTutorialSample() {
		Instrument instrument = cryptoInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account();
		Holding holding = Holding.create(account, instrument, NOW.minusDays(1));
		holding.applyBuy(new BigDecimal("1"), new BigDecimal("900000"), NOW.minusDays(1));
		holding.reserveQuantity(new BigDecimal("0.1"));
		Order order = limitPendingOrder(account, instrument, OrderSide.SELL, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);
		when(portfolioSellService.getHoldingForUpdate(account, instrument)).thenReturn(holding);
		SellAllocationDto allocation = new SellAllocationDto(90_000L, 40L);
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(new BigDecimal("0.1")), eq(NOW)))
			.thenReturn(allocation);
		when(portfolioSellService.finalizeSellRealizedPnl(eq(account), any(Trade.class), eq(100_000L), eq(50L),
			eq(allocation), eq(NOW))).thenReturn(0L);

		service.fillIfPending(order.getId());

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void fillIfPendingSecondCallDoesNotDuplicateFirstCallSideEffectsOnSameOrder() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());
		long cashAfterFirstCall = account.getCashBalance();
		long reservedCashAfterFirstCall = account.getReservedCash();

		service.fillIfPending(order.getId());

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(account.getCashBalance()).isEqualTo(cashAfterFirstCall);
		assertThat(account.getReservedCash()).isEqualTo(reservedCashAfterFirstCall);
		verify(tradeRepository, org.mockito.Mockito.times(1)).save(any(Trade.class));
		verify(portfolioBuyService, org.mockito.Mockito.times(1)).applyBuyTrade(
			any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
		verify(accountService, org.mockito.Mockito.times(1)).getAccountByIdForUpdate(account.getId());
	}

	@Test
	void fillIfPendingIsNoOpWhenOrderAlreadyFilled() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		order.markFilled();
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

		service.fillIfPending(order.getId());

		verify(accountService, never()).getAccountByIdForUpdate(any());
		verifyNoInteractions(tradeRepository, portfolioBuyService, portfolioSellService, eventPublisher);
	}

	@Test
	void fillBatchFillsEachOrderInGivenOrder() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order first = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		Order second = limitPendingOrder(account, instrument, OrderSide.BUY, "0.2", "1000000");
		ReflectionTestUtils.setField(first, "id", 101L);
		ReflectionTestUtils.setField(second, "id", 102L);
		account.reserveCash(100_050L + 200_100L);
		when(orderRepository.findByIdInForUpdate(List.of(101L, 102L))).thenReturn(List.of(first, second));
		when(accountService.getAccountsByIdsForUpdate(List.of(10L))).thenReturn(List.of(account));
		when(portfolioBuyService.findExistingHoldingsForChunkUpdate(List.of(10L), instrument.getId()))
			.thenReturn(List.of());
		when(portfolioBuyService.applyBuyTrade(
			eq(account), eq(instrument), any(Trade.class), any(BigDecimal.class), eq(new BigDecimal("1000000")),
			anyLong(), eq(NOW), any(Holding.class)))
			.thenAnswer(invocation -> invocation.getArgument(7));

		service.fillBatch(List.of(101L, 102L));

		assertThat(first.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(second.getStatus()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	void fillBatchSkipsOrderThatIsNoLongerPending() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order alreadyFilled = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		alreadyFilled.markFilled();
		ReflectionTestUtils.setField(alreadyFilled, "id", 103L);
		when(orderRepository.findByIdInForUpdate(List.of(103L))).thenReturn(List.of(alreadyFilled));

		service.fillBatch(List.of(103L));

		verify(accountService, never()).getAccountsByIdsForUpdate(any());
		verifyNoInteractions(tradeRepository, portfolioBuyService, portfolioSellService, eventPublisher);
	}

	@Test
	void fillBatchLocksBulkResourcesInOrderAccountHoldingSequence() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		ReflectionTestUtils.setField(order, "id", 101L);
		when(orderRepository.findByIdInForUpdate(List.of(101L))).thenReturn(List.of(order));
		when(accountService.getAccountsByIdsForUpdate(List.of(10L))).thenReturn(List.of(account));
		when(portfolioBuyService.findExistingHoldingsForChunkUpdate(List.of(10L), instrument.getId()))
			.thenReturn(List.of());
		when(portfolioBuyService.applyBuyTrade(
			eq(account), eq(instrument), any(Trade.class), eq(new BigDecimal("0.1")), eq(new BigDecimal("1000000")),
			eq(50L), eq(NOW), any(Holding.class)))
			.thenAnswer(invocation -> invocation.getArgument(7));

		service.fillBatch(List.of(101L));

		InOrder bulkLockOrder = inOrder(orderRepository, accountService, portfolioBuyService);
		bulkLockOrder.verify(orderRepository).findByIdInForUpdate(List.of(101L));
		bulkLockOrder.verify(accountService).getAccountsByIdsForUpdate(List.of(10L));
		bulkLockOrder.verify(portfolioBuyService).findExistingHoldingsForChunkUpdate(List.of(10L), instrument.getId());
		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	@SuppressWarnings("unchecked")
	void fillBatchLocksOrderIdsAscendingButProcessesGivenSequenceAndReusesHoldingCreatedInSameChunk() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(200_100L + 100_050L);
		Order order101 = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		ReflectionTestUtils.setField(order101, "id", 101L);
		Order order102 = limitPendingOrder(account, instrument, OrderSide.BUY, "0.2", "1000000");
		ReflectionTestUtils.setField(order102, "id", 102L);
		when(orderRepository.findByIdInForUpdate(List.of(101L, 102L))).thenReturn(List.of(order101, order102));
		when(accountService.getAccountsByIdsForUpdate(List.of(10L))).thenReturn(List.of(account));
		when(portfolioBuyService.findExistingHoldingsForChunkUpdate(List.of(10L), instrument.getId()))
			.thenReturn(List.of());
		when(portfolioBuyService.applyBuyTrade(
			eq(account), eq(instrument), any(Trade.class), any(BigDecimal.class), eq(new BigDecimal("1000000")),
			anyLong(), eq(NOW), any(Holding.class)))
			.thenAnswer(invocation -> invocation.getArgument(7));

		service.fillBatch(List.of(102L, 101L));

		ArgumentCaptor<List<Long>> lockIdsCaptor = ArgumentCaptor.forClass(List.class);
		verify(orderRepository).findByIdInForUpdate(lockIdsCaptor.capture());
		assertThat(lockIdsCaptor.getValue()).containsExactly(101L, 102L);

		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository, org.mockito.Mockito.times(2)).save(tradeCaptor.capture());
		List<Trade> savedTrades = tradeCaptor.getAllValues();
		assertThat(savedTrades.get(0).getQuantity()).isEqualByComparingTo("0.2");
		assertThat(savedTrades.get(1).getQuantity()).isEqualByComparingTo("0.1");

		ArgumentCaptor<Holding> holdingCaptor = ArgumentCaptor.forClass(Holding.class);
		verify(portfolioBuyService, org.mockito.Mockito.times(2)).applyBuyTrade(
			eq(account), eq(instrument), any(Trade.class), any(BigDecimal.class), eq(new BigDecimal("1000000")),
			anyLong(), eq(NOW), holdingCaptor.capture());
		List<Holding> holdingsPassedIn = holdingCaptor.getAllValues();
		assertThat(holdingsPassedIn.get(1)).isSameAs(holdingsPassedIn.get(0));

		assertThat(order101.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(order102.getStatus()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	void fillBatchThrowsSameMessageAsFindByIdForUpdateWhenBulkOrderLockOmitsRequestedId() {
		when(orderRepository.findByIdInForUpdate(List.of(999L))).thenReturn(List.of());

		assertThatThrownBy(() -> service.fillBatch(List.of(999L)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("체결 대상 주문을 찾을 수 없습니다. orderId=999");

		verifyNoInteractions(accountService, tradeRepository, portfolioBuyService, portfolioSellService);
	}

	@Test
	void fillBatchThrowsExplicitExceptionWhenBulkAccountLockOmitsOrdersAccount() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		ReflectionTestUtils.setField(order, "id", 105L);
		when(orderRepository.findByIdInForUpdate(List.of(105L))).thenReturn(List.of(order));
		when(accountService.getAccountsByIdsForUpdate(List.of(10L))).thenReturn(List.of());
		when(portfolioBuyService.findExistingHoldingsForChunkUpdate(List.of(10L), instrument.getId()))
			.thenReturn(List.of());

		assertThatThrownBy(() -> service.fillBatch(List.of(105L)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("체결 대상 계좌를 찾을 수 없습니다. accountId=" + account.getId());

		verify(portfolioBuyService, never())
			.applyBuyTrade(any(), any(), any(), any(), any(), anyLong(), any());
		verify(portfolioBuyService, never())
			.applyBuyTrade(any(), any(), any(), any(), any(), anyLong(), any(), any());
		verifyNoInteractions(tradeRepository, portfolioSellService);
	}

	@Test
	void fillBatchThrowsSameMessageAsGetHoldingForUpdateWhenSellHoldingMissingFromBulkMap() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(account, instrument, OrderSide.SELL, "0.1", "1000000");
		ReflectionTestUtils.setField(order, "id", 104L);
		when(orderRepository.findByIdInForUpdate(List.of(104L))).thenReturn(List.of(order));
		when(accountService.getAccountsByIdsForUpdate(List.of(10L))).thenReturn(List.of(account));
		when(portfolioBuyService.findExistingHoldingsForChunkUpdate(List.of(10L), instrument.getId()))
			.thenReturn(List.of());

		assertThatThrownBy(() -> service.fillBatch(List.of(104L)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage(
				"체결 대상 holding을 찾을 수 없습니다. accountId=" + account.getId() + ", instrumentId=" + instrument.getId());

		verifyNoInteractions(tradeRepository, portfolioSellService, eventPublisher);
	}

	@Test
	void fillBatchDoesNotPublishRealizedPnlUpdatedEventWhenSellInstrumentIsTutorialSample() {
		Instrument instrument = cryptoInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account();
		Holding holding = Holding.create(account, instrument, NOW.minusDays(1));
		holding.applyBuy(new BigDecimal("1"), new BigDecimal("900000"), NOW.minusDays(1));
		holding.reserveQuantity(new BigDecimal("0.1"));
		Order order = limitPendingOrder(account, instrument, OrderSide.SELL, "0.1", "1000000");
		ReflectionTestUtils.setField(order, "id", 106L);
		when(orderRepository.findByIdInForUpdate(List.of(106L))).thenReturn(List.of(order));
		when(accountService.getAccountsByIdsForUpdate(List.of(10L))).thenReturn(List.of(account));
		when(portfolioBuyService.findExistingHoldingsForChunkUpdate(List.of(10L), instrument.getId()))
			.thenReturn(List.of(holding));
		SellAllocationDto allocation = new SellAllocationDto(90_000L, 40L);
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(new BigDecimal("0.1")), eq(NOW)))
			.thenReturn(allocation);
		when(portfolioSellService.finalizeSellRealizedPnl(eq(account), any(Trade.class), eq(100_000L), eq(50L),
			eq(allocation), eq(NOW))).thenReturn(0L);

		service.fillBatch(List.of(106L));

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
		verifyNoInteractions(eventPublisher);
	}

	private static Order limitPendingOrder(
		Account account, Instrument instrument, OrderSide side, String quantity, String limitPrice) {
		Order order = Order.createLimitPending(
			account.getUser(), account, instrument, side, new BigDecimal(quantity), new BigDecimal(limitPrice),
			"idem-fill-" + side, "a".repeat(64), NOW);
		ReflectionTestUtils.setField(order, "id", 100L);
		return order;
	}

	private static Order attributedLimitPendingBuyOrder(Account account, Instrument instrument) {
		Order order = Order.createPracticeLimitPendingBuyForAttempt(
			account.getUser(), account, instrument, new BigDecimal("0.1"), new BigDecimal("1000000"),
			40L, 20L, 1L, "idem-attributed-fill", "c".repeat(64), NOW);
		ReflectionTestUtils.setField(order, "id", 101L);
		return order;
	}

	private static Instrument cryptoInstrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), 5_000L, true,
			NOW);
		ReflectionTestUtils.setField(instrument, "id", 1L);
		return instrument;
	}

	private static Account account() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		Account account = Account.create(user, Market.CRYPTO, NOW);
		ReflectionTestUtils.setField(account, "id", 10L);
		return account;
	}

	private static TutorialAccount tutorialAccount() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		return TutorialAccount.create(user, Market.CRYPTO, NOW);
	}
}
