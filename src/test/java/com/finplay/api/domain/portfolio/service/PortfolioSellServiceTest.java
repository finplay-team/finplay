package com.finplay.api.domain.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.entity.TradeAllocation;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class PortfolioSellServiceTest {

	private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 7, 20, 9, 0, 0);
	private static final LocalDateTime LATER = LocalDateTime.of(2026, 7, 25, 9, 0, 0);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	private final HoldingRepository holdingRepository = Mockito.mock(HoldingRepository.class);
	private final HoldingLotRepository holdingLotRepository = Mockito.mock(HoldingLotRepository.class);
	private final TradeAllocationRepository tradeAllocationRepository = Mockito.mock(TradeAllocationRepository.class);
	private final TutorialAccountService tutorialAccountService = Mockito.mock(TutorialAccountService.class);
	private final PortfolioSellService service = new PortfolioSellService(holdingRepository, holdingLotRepository,
		tradeAllocationRepository, tutorialAccountService);


	@Test
	void getHoldingForUpdateOrThrowThrowsInsufficientQtyWhenHoldingDoesNotExist() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		when(holdingRepository.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId()))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getHoldingForUpdateOrThrow(account, instrument, new BigDecimal("1")))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.INSUFFICIENT_QTY));
	}

	@Test
	void getHoldingForUpdateOrThrowThrowsInsufficientQtyWhenAvailableQuantityIsLessThanRequired() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding holding = Holding.create(account, instrument, EARLIER);
		holding.applyBuy(new BigDecimal("5"), new BigDecimal("100"), EARLIER);
		holding.reserveQuantity(new BigDecimal("4"));
		when(holdingRepository.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId()))
			.thenReturn(Optional.of(holding));

		assertThatThrownBy(() -> service.getHoldingForUpdateOrThrow(account, instrument, new BigDecimal("2")))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.INSUFFICIENT_QTY));
	}

	@Test
	void getHoldingForUpdateOrThrowReturnsHoldingWhenAvailableQuantityIsSufficient() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding holding = Holding.create(account, instrument, EARLIER);
		holding.applyBuy(new BigDecimal("5"), new BigDecimal("100"), EARLIER);
		when(holdingRepository.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId()))
			.thenReturn(Optional.of(holding));

		Holding result = service.getHoldingForUpdateOrThrow(account, instrument, new BigDecimal("5"));

		assertThat(result).isSameAs(holding);
	}

	@Test
	void getHoldingForUpdateReturnsHoldingWhenFound() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding holding = Holding.create(account, instrument, EARLIER);
		when(holdingRepository.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId()))
			.thenReturn(Optional.of(holding));

		Holding result = service.getHoldingForUpdate(account, instrument);

		assertThat(result).isSameAs(holding);
	}

	@Test
	void getHoldingForUpdateThrowsIllegalStateExceptionWhenHoldingNotFound() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		when(holdingRepository.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId()))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getHoldingForUpdate(account, instrument))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void finalizeSellRealizedPnlComputesRealizedPnlAndUpdatesTradeAndAccount() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Trade sellTrade = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("150"),
			new BigDecimal("10"), 1500L, 4L, NOW);
		SellAllocationDto allocation = new SellAllocationDto(1000L, 30L);
		long cashBeforeSell = account.getCashBalance();
		long realizedPnlBeforeSell = account.getRealizedPnl();

		long realizedPnl = service.finalizeSellRealizedPnl(account, sellTrade, 1500L, 4L, allocation, NOW);

		assertThat(realizedPnl).isEqualTo(466L);
		assertThat(sellTrade.getRealizedPnl()).isEqualTo(466L);
		assertThat(account.getCashBalance()).isEqualTo(cashBeforeSell + 1500L - 4L);
		assertThat(account.getRealizedPnl()).isEqualTo(realizedPnlBeforeSell + 466L);
		verifyNoInteractions(tutorialAccountService);
	}

	@Test
	void finalizeSellRealizedPnlCreditsTutorialAccountAndLeavesRealAccountCashAndRealizedPnlUnchangedWhenInstrumentIsTutorialSample() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Trade sellTrade = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("150"),
			new BigDecimal("10"), 1500L, 4L, NOW);
		SellAllocationDto allocation = new SellAllocationDto(1000L, 30L);
		long cashBeforeSell = account.getCashBalance();
		long realizedPnlBeforeSell = account.getRealizedPnl();
		TutorialAccount tutorialAccount = TutorialAccount.create(
			account.getUser(), Market.STOCK, EARLIER);
		when(tutorialAccountService.getOrCreateForUpdate(
			any(), eq(Market.STOCK), eq(NOW)))
			.thenReturn(tutorialAccount);
		long tutorialCashBeforeSell = tutorialAccount.getCashBalance();

		long realizedPnl = service.finalizeSellRealizedPnl(account, sellTrade, 1500L, 4L, allocation, NOW);

		assertThat(realizedPnl).isEqualTo(466L);
		assertThat(sellTrade.getRealizedPnl()).isEqualTo(466L);
		assertThat(account.getCashBalance()).isEqualTo(cashBeforeSell);
		assertThat(account.getRealizedPnl()).isEqualTo(realizedPnlBeforeSell);
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(tutorialCashBeforeSell + 1500L - 4L);
		assertThat(tutorialAccount.getRealizedPnl()).isEqualTo(466L);
	}

	@Test
	void applySellTradeFullyConsumesSingleLotAndAllocatesExactBuyTradeAmount() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding holding = testHolding(account, instrument, new BigDecimal("10"));
		Trade buyTrade = testTrade(account, instrument, OrderSide.BUY, new BigDecimal("100"),
			new BigDecimal("10"), 1000L, 30L, EARLIER);
		HoldingLot lot = buildLot(1L, holding, buyTrade, new BigDecimal("10"), new BigDecimal("100"), 30L, EARLIER);
		when(holdingLotRepository.findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
			holding.getId(), BigDecimal.ZERO)).thenReturn(List.of(lot));
		when(tradeAllocationRepository.sumAllocatedCostByHoldingLotId(1L)).thenReturn(0L);
		when(tradeAllocationRepository.sumAllocatedBuyFeeByHoldingLotId(1L)).thenReturn(0L);
		Trade sellTrade = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("150"),
			new BigDecimal("10"), 1500L, 4L, NOW);

		SellAllocationDto result = service.applySellTrade(holding, sellTrade, new BigDecimal("10"), NOW);

		assertThat(result.totalAllocatedCost()).isEqualTo(1000L);
		assertThat(result.totalAllocatedBuyFee()).isEqualTo(30L);
		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo("0");

		ArgumentCaptor<TradeAllocation> allocationCaptor = ArgumentCaptor.forClass(TradeAllocation.class);
		verify(tradeAllocationRepository).save(allocationCaptor.capture());
		TradeAllocation savedAllocation = allocationCaptor.getValue();
		assertThat(savedAllocation.getAllocatedQuantity()).isEqualByComparingTo("10");
		assertThat(savedAllocation.getAllocatedCost()).isEqualTo(1000L);
		assertThat(savedAllocation.getAllocatedBuyFee()).isEqualTo(30L);

		verify(holdingLotRepository).save(lot);
		ArgumentCaptor<Holding> holdingCaptor = ArgumentCaptor.forClass(Holding.class);
		verify(holdingRepository).save(holdingCaptor.capture());
		assertThat(holdingCaptor.getValue().getQuantity()).isEqualByComparingTo("0");
		assertThat(holdingCaptor.getValue().isActive()).isFalse();
	}

	@Test
	void applySellTradeConsumesMultipleLotsInFifoOrderWhenSingleLotIsInsufficient() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding holding = testHolding(account, instrument, new BigDecimal("10"));
		Trade buyTrade1 = testTrade(account, instrument, OrderSide.BUY, new BigDecimal("100"),
			new BigDecimal("5"), 500L, 15L, EARLIER);
		HoldingLot lot1 = buildLot(1L, holding, buyTrade1, new BigDecimal("5"), new BigDecimal("100"), 15L, EARLIER);
		Trade buyTrade2 = testTrade(account, instrument, OrderSide.BUY, new BigDecimal("200"),
			new BigDecimal("5"), 1000L, 30L, LATER);
		HoldingLot lot2 = buildLot(2L, holding, buyTrade2, new BigDecimal("5"), new BigDecimal("200"), 30L, LATER);
		when(holdingLotRepository.findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
			holding.getId(), BigDecimal.ZERO)).thenReturn(List.of(lot1, lot2));
		when(tradeAllocationRepository.sumAllocatedCostByHoldingLotId(1L)).thenReturn(0L);
		when(tradeAllocationRepository.sumAllocatedBuyFeeByHoldingLotId(1L)).thenReturn(0L);
		Trade sellTrade = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("300"),
			new BigDecimal("8"), 2400L, 7L, NOW);

		SellAllocationDto result = service.applySellTrade(holding, sellTrade, new BigDecimal("8"), NOW);

		assertThat(lot1.getRemainingQuantity()).isEqualByComparingTo("0");
		assertThat(lot2.getRemainingQuantity()).isEqualByComparingTo("2");

		assertThat(result.totalAllocatedCost()).isEqualTo(500L + 600L);
		assertThat(result.totalAllocatedBuyFee()).isEqualTo(15L + 18L);

		ArgumentCaptor<TradeAllocation> allocationCaptor = ArgumentCaptor.forClass(TradeAllocation.class);
		InOrder inOrder = Mockito.inOrder(holdingLotRepository, tradeAllocationRepository);
		inOrder.verify(holdingLotRepository).save(lot1);
		inOrder.verify(tradeAllocationRepository).save(allocationCaptor.capture());
		inOrder.verify(holdingLotRepository).save(lot2);
		inOrder.verify(tradeAllocationRepository).save(allocationCaptor.capture());

		List<TradeAllocation> savedAllocations = allocationCaptor.getAllValues();
		assertThat(savedAllocations.get(0).getHoldingLot()).isSameAs(lot1);
		assertThat(savedAllocations.get(0).getAllocatedQuantity()).isEqualByComparingTo("5");
		assertThat(savedAllocations.get(0).getAllocatedCost()).isEqualTo(500L);
		assertThat(savedAllocations.get(0).getAllocatedBuyFee()).isEqualTo(15L);
		assertThat(savedAllocations.get(1).getHoldingLot()).isSameAs(lot2);
		assertThat(savedAllocations.get(1).getAllocatedQuantity()).isEqualByComparingTo("3");
		assertThat(savedAllocations.get(1).getAllocatedCost()).isEqualTo(600L);
		assertThat(savedAllocations.get(1).getAllocatedBuyFee()).isEqualTo(18L);
	}

	@Test
	void applySellTradeLeavesRemainingQuantityWhenSellQuantityIsLessThanLotRemaining() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding holding = testHolding(account, instrument, new BigDecimal("10"));
		Trade buyTrade = testTrade(account, instrument, OrderSide.BUY, new BigDecimal("300"),
			new BigDecimal("10"), 3000L, 100L, EARLIER);
		HoldingLot lot = buildLot(1L, holding, buyTrade, new BigDecimal("10"), new BigDecimal("300"), 100L, EARLIER);
		when(holdingLotRepository.findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
			holding.getId(), BigDecimal.ZERO)).thenReturn(List.of(lot));
		Trade sellTrade = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("300"),
			new BigDecimal("4"), 1200L, 4L, NOW);

		SellAllocationDto result = service.applySellTrade(holding, sellTrade, new BigDecimal("4"), NOW);

		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo("6");
		assertThat(result.totalAllocatedCost()).isEqualTo(1200L);
		assertThat(result.totalAllocatedBuyFee()).isEqualTo(40L);
		verify(tradeAllocationRepository).save(Mockito.any(TradeAllocation.class));
		Mockito.verify(tradeAllocationRepository, Mockito.never()).sumAllocatedCostByHoldingLotId(Mockito.anyLong());
		Mockito.verify(tradeAllocationRepository, Mockito.never())
			.sumAllocatedBuyFeeByHoldingLotId(Mockito.anyLong());
	}

	@Test
	void applySellTradeAbsorbsRoundingRemainderExactlyOnLastAllocationOfLot() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding holding = testHolding(account, instrument, new BigDecimal("1"));
		Trade buyTrade = testTrade(account, instrument, OrderSide.BUY, new BigDecimal("10"),
			new BigDecimal("3"), 30L, 100L, EARLIER);
		HoldingLot lot = buildLot(1L, holding, buyTrade, new BigDecimal("3"), new BigDecimal("10"), 100L, EARLIER);
		lot.consume(new BigDecimal("2"));
		when(holdingLotRepository.findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
			holding.getId(), BigDecimal.ZERO)).thenReturn(List.of(lot));
		when(tradeAllocationRepository.sumAllocatedCostByHoldingLotId(1L)).thenReturn(20L);
		when(tradeAllocationRepository.sumAllocatedBuyFeeByHoldingLotId(1L)).thenReturn(66L);
		Trade sellTrade = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("15"),
			new BigDecimal("1"), 15L, 1L, NOW);

		SellAllocationDto result = service.applySellTrade(holding, sellTrade, new BigDecimal("1"), NOW);

		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo("0");
		assertThat(result.totalAllocatedCost()).isEqualTo(10L);
		assertThat(result.totalAllocatedBuyFee()).isEqualTo(34L);
	}

	@Test
	void applySellTradeAccumulatesRoundingAcrossMultiplePartialSellsAndAbsorbsRemainderOnFinalSell() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding holding = testHolding(account, instrument, new BigDecimal("10"));
		Trade buyTrade = testTrade(account, instrument, OrderSide.BUY, new BigDecimal("33.3"),
			new BigDecimal("10"), 333L, 100L, EARLIER);
		HoldingLot lot = buildLot(
			1L, holding, buyTrade, new BigDecimal("10"), new BigDecimal("33.33333333"), 100L, EARLIER);
		when(holdingLotRepository.findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
			holding.getId(), BigDecimal.ZERO)).thenReturn(List.of(lot));

		Trade sellTrade1 = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("40"),
			new BigDecimal("3"), 120L, 1L, NOW);
		SellAllocationDto result1 = service.applySellTrade(holding, sellTrade1, new BigDecimal("3"), NOW);
		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo("7");
		assertThat(result1.totalAllocatedCost()).isEqualTo(99L);
		assertThat(result1.totalAllocatedBuyFee()).isEqualTo(30L);

		Trade sellTrade2 = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("40"),
			new BigDecimal("3"), 120L, 1L, NOW);
		SellAllocationDto result2 = service.applySellTrade(holding, sellTrade2, new BigDecimal("3"), NOW);
		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo("4");
		assertThat(result2.totalAllocatedCost()).isEqualTo(99L);
		assertThat(result2.totalAllocatedBuyFee()).isEqualTo(30L);

		long previousCost = result1.totalAllocatedCost() + result2.totalAllocatedCost();
		long previousBuyFee = result1.totalAllocatedBuyFee() + result2.totalAllocatedBuyFee();
		when(tradeAllocationRepository.sumAllocatedCostByHoldingLotId(1L)).thenReturn(previousCost);
		when(tradeAllocationRepository.sumAllocatedBuyFeeByHoldingLotId(1L)).thenReturn(previousBuyFee);

		Trade sellTrade3 = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("40"),
			new BigDecimal("4"), 160L, 2L, NOW);
		SellAllocationDto result3 = service.applySellTrade(holding, sellTrade3, new BigDecimal("4"), NOW);

		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo("0");
		assertThat(result3.totalAllocatedCost()).isEqualTo(buyTrade.getAmount() - previousCost);
		assertThat(result3.totalAllocatedBuyFee()).isEqualTo(lot.getBuyFee() - previousBuyFee);

		long totalCostAcrossThreeSells = result1.totalAllocatedCost() + result2.totalAllocatedCost()
			+ result3.totalAllocatedCost();
		long totalBuyFeeAcrossThreeSells = result1.totalAllocatedBuyFee() + result2.totalAllocatedBuyFee()
			+ result3.totalAllocatedBuyFee();
		assertThat(totalCostAcrossThreeSells).isEqualTo(buyTrade.getAmount());
		assertThat(totalBuyFeeAcrossThreeSells).isEqualTo(lot.getBuyFee());
	}

	private static Account testAccount() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", EARLIER);
		Account account = Account.create(user, Market.STOCK, EARLIER);
		ReflectionTestUtils.setField(account, "id", 1L);
		return account;
	}

	private static Instrument testInstrument() {
		Instrument instrument = Instrument.create(
			Market.STOCK,
			"005930",
			"삼성전자",
			new BigDecimal("100"),
			0L,
			true,
			EARLIER);
		ReflectionTestUtils.setField(instrument, "id", 1L);
		return instrument;
	}

	private static Holding testHolding(Account account, Instrument instrument, BigDecimal quantity) {
		Holding holding = Holding.create(account, instrument, EARLIER);
		holding.applyBuy(quantity, BigDecimal.ONE, EARLIER);
		ReflectionTestUtils.setField(holding, "id", 1L);
		return holding;
	}

	private static HoldingLot buildLot(
		Long id,
		Holding holding,
		Trade buyTrade,
		BigDecimal quantity,
		BigDecimal unitCost,
		long buyFee,
		LocalDateTime executedAt) {
		HoldingLot lot = HoldingLot.create(holding, buyTrade, quantity, unitCost, buyFee, executedAt, executedAt);
		ReflectionTestUtils.setField(lot, "id", id);
		return lot;
	}

	private static Trade testTrade(
		Account account,
		Instrument instrument,
		OrderSide side,
		BigDecimal price,
		BigDecimal quantity,
		long amount,
		long fee,
		LocalDateTime executedAt) {
		Order order = Order.create(
			account.getUser(),
			account,
			instrument,
			side,
			OrderType.MARKET,
			quantity,
			"idem-key-" + side + executedAt,
			"a".repeat(64),
			executedAt);
		return Trade.of(order, account, instrument,
			com.finplay.api.domain.market.entity.StockReplaySession.ready(executedAt.toLocalDate(),
				executedAt.toLocalDate(),
				executedAt, executedAt),
			side, price, quantity,
			amount, fee, null, executedAt, executedAt);
	}
}
