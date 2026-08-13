// FIFO lot 소비·원 단위 잔여 처리·보유수량 검증 규칙을 검증하는 단위 테스트다.
package com.finplay.api.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.domain.HoldingLot;
import com.finplay.api.portfolio.domain.TradeAllocation;
import com.finplay.api.portfolio.repository.HoldingLotRepository;
import com.finplay.api.portfolio.repository.HoldingRepository;
import com.finplay.api.portfolio.repository.TradeAllocationRepository;
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
	private final PortfolioSellService service = new PortfolioSellService(holdingRepository, holdingLotRepository,
		tradeAllocationRepository);

	// getHoldingOrThrow(availableQuantity 미검증 구버전)는 015-limit-order 항목5에서 유일 호출부(시장가 매도)가
	// getHoldingForUpdateOrThrow로 대체되며 프로덕션 코드에서 완전히 제거됐다 — 이 테스트들도 함께 제거한다.

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
		// 다른 지정가 매도로 이미 일부가 예약된 경우(availableQuantity = quantity - reservedQuantity)를 검증한다
		// (spec.md: 기존 시장가 SELL도 예약 원장을 반영해 예약된 수량을 중복 매도할 수 없다).
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding holding = Holding.create(account, instrument, EARLIER);
		holding.applyBuy(new BigDecimal("5"), new BigDecimal("100"), EARLIER);
		holding.reserveQuantity(new BigDecimal("4"));
		when(holdingRepository.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId()))
			.thenReturn(Optional.of(holding));

		// availableQuantity = 5 - 4 = 1 < 요청 2
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
		// 015-limit-order LMT-002: 예약된 holding이 없는 상태는 원장 불변식 위반이라 방어적으로 예외를 던진다.
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

		long realizedPnl = service.finalizeSellRealizedPnl(account, sellTrade, 1500L, 4L, allocation);

		// realizedPnl = (매도금액 - 매도수수료) - (배분원가 + 배분매수수수료) = (1500-4) - (1000+30) = 466
		assertThat(realizedPnl).isEqualTo(466L);
		assertThat(sellTrade.getRealizedPnl()).isEqualTo(466L);
		assertThat(account.getCashBalance()).isEqualTo(cashBeforeSell + 1500L - 4L);
		assertThat(account.getRealizedPnl()).isEqualTo(realizedPnlBeforeSell + 466L);
	}

	@Test
	void finalizeSellRealizedPnlSkipsAccountRealizedPnlWhenInstrumentIsTutorialSample() {
		// spec 033 SANDBOX-EXCL-004: 샌드박스 종목 매도는 account.realizedPnl에 반영하지 않지만
		// trade.realizedPnl·account.cashBalance는 항상 그대로 반영된다.
		Account account = testAccount();
		Instrument instrument = testInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Trade sellTrade = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("150"),
			new BigDecimal("10"), 1500L, 4L, NOW);
		SellAllocationDto allocation = new SellAllocationDto(1000L, 30L);
		long cashBeforeSell = account.getCashBalance();
		long realizedPnlBeforeSell = account.getRealizedPnl();

		long realizedPnl = service.finalizeSellRealizedPnl(account, sellTrade, 1500L, 4L, allocation);

		assertThat(realizedPnl).isEqualTo(466L);
		assertThat(sellTrade.getRealizedPnl()).isEqualTo(466L);
		assertThat(account.getCashBalance()).isEqualTo(cashBeforeSell + 1500L - 4L);
		assertThat(account.getRealizedPnl()).isEqualTo(realizedPnlBeforeSell);
		// spec 033 SANDBOX-EXCL-006 call site #3: 샌드박스 매도 입금은 sandboxCashAdjustment에 누적된다.
		assertThat(account.getSandboxCashAdjustment()).isEqualTo(1500L - 4L);
	}

	@Test
	void finalizeSellRealizedPnlDoesNotAccumulateSandboxCashAdjustmentWhenInstrumentIsReal() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Trade sellTrade = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("150"),
			new BigDecimal("10"), 1500L, 4L, NOW);
		SellAllocationDto allocation = new SellAllocationDto(1000L, 30L);

		service.finalizeSellRealizedPnl(account, sellTrade, 1500L, 4L, allocation);

		assertThat(account.getSandboxCashAdjustment()).isEqualTo(0L);
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

		// 8주 매도: lot1(5주 전량) + lot2(3주 부분) 소비 — lot2는 마지막 배분이 아니므로 비례 FLOOR
		SellAllocationDto result = service.applySellTrade(holding, sellTrade, new BigDecimal("8"), NOW);

		assertThat(lot1.getRemainingQuantity()).isEqualByComparingTo("0");
		assertThat(lot2.getRemainingQuantity()).isEqualByComparingTo("2");

		// lot1: 전량 소진 → allocatedCost=500(=buyTrade.amount-0), allocatedBuyFee=15
		// lot2: 3/5 비례 FLOOR → cost = floor(200*3)=600, fee = floor(30*3/5)=18
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

		// 부분 매도(4/10) — 비례 FLOOR: cost = floor(300*4)=1200, fee = floor(100*4/10)=40
		SellAllocationDto result = service.applySellTrade(holding, sellTrade, new BigDecimal("4"), NOW);

		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo("6");
		assertThat(result.totalAllocatedCost()).isEqualTo(1200L);
		assertThat(result.totalAllocatedBuyFee()).isEqualTo(40L);
		// 마지막 배분이 아니므로 SUM 조회 없이 곧바로 배분이 저장된다
		verify(tradeAllocationRepository).save(Mockito.any(TradeAllocation.class));
		Mockito.verify(tradeAllocationRepository, Mockito.never()).sumAllocatedCostByHoldingLotId(Mockito.anyLong());
		Mockito.verify(tradeAllocationRepository, Mockito.never())
			.sumAllocatedBuyFeeByHoldingLotId(Mockito.anyLong());
	}

	@Test
	void applySellTradeAbsorbsRoundingRemainderExactlyOnLastAllocationOfLot() {
		// 원 단위 잔여 처리 경계값: buyFee=100, originalQuantity=3인 lot을 두 번에 걸쳐(1주+1주) 부분 소비했다고 가정하고
		// 마지막 1주(=lot 소진)를 매도할 때 누적 낙전(100 - 66 = 34)이 정확히 흡수되는지 검증한다.
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding holding = testHolding(account, instrument, new BigDecimal("1"));
		Trade buyTrade = testTrade(account, instrument, OrderSide.BUY, new BigDecimal("10"),
			new BigDecimal("3"), 30L, 100L, EARLIER);
		HoldingLot lot = buildLot(1L, holding, buyTrade, new BigDecimal("3"), new BigDecimal("10"), 100L, EARLIER);
		lot.consume(new BigDecimal("2")); // 이전 두 번의 부분 매도로 이미 2주가 소비된 상태(잔여 1주)
		when(holdingLotRepository.findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
			holding.getId(), BigDecimal.ZERO)).thenReturn(List.of(lot));
		when(tradeAllocationRepository.sumAllocatedCostByHoldingLotId(1L)).thenReturn(20L);
		when(tradeAllocationRepository.sumAllocatedBuyFeeByHoldingLotId(1L)).thenReturn(66L);
		Trade sellTrade = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("15"),
			new BigDecimal("1"), 15L, 1L, NOW);

		SellAllocationDto result = service.applySellTrade(holding, sellTrade, new BigDecimal("1"), NOW);

		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo("0");
		// allocatedCost = buyTrade.amount(30) - previousCost(20) = 10
		// allocatedBuyFee = lot.buyFee(100) - previousBuyFee(66) = 34
		assertThat(result.totalAllocatedCost()).isEqualTo(10L);
		assertThat(result.totalAllocatedBuyFee()).isEqualTo(34L);
	}

	@Test
	void applySellTradeAccumulatesRoundingAcrossMultiplePartialSellsAndAbsorbsRemainderOnFinalSell() {
		// 여러 번(서로 다른 매도 트랜잭션)에 걸쳐 같은 lot(수량 10)을 3+3+4로 나눠 매도하는 케이스.
		// 앞의 두 번(3+3)은 비례식 FLOOR로 낙전(반올림 손실)이 발생하고, lot이 정확히 소진되는
		// 세 번째(마지막) 배분에서 "buyTrade.amount/lot.buyFee - 누적 배분값"으로 그 낙전이
		// 정확히 흡수되어 3번의 배분 합이 buyTrade.amount·lot.buyFee와 원 단위까지 일치하는지 검증한다.
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding holding = testHolding(account, instrument, new BigDecimal("10"));
		Trade buyTrade = testTrade(account, instrument, OrderSide.BUY, new BigDecimal("33.3"),
			new BigDecimal("10"), 333L, 100L, EARLIER);
		HoldingLot lot = buildLot(
			1L, holding, buyTrade, new BigDecimal("10"), new BigDecimal("33.33333333"), 100L, EARLIER);
		when(holdingLotRepository.findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(
			holding.getId(), BigDecimal.ZERO)).thenReturn(List.of(lot));

		// 1번째 매도: 3주 (부분 소비, 마지막 아님) — cost=floor(33.33333333*3)=99, fee=floor(100*3/10)=30
		Trade sellTrade1 = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("40"),
			new BigDecimal("3"), 120L, 1L, NOW);
		SellAllocationDto result1 = service.applySellTrade(holding, sellTrade1, new BigDecimal("3"), NOW);
		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo("7");
		assertThat(result1.totalAllocatedCost()).isEqualTo(99L);
		assertThat(result1.totalAllocatedBuyFee()).isEqualTo(30L);

		// 2번째 매도: 3주 (여전히 부분 소비, 마지막 아님) — cost=floor(33.33333333*3)=99, fee=floor(100*3/10)=30
		Trade sellTrade2 = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("40"),
			new BigDecimal("3"), 120L, 1L, NOW);
		SellAllocationDto result2 = service.applySellTrade(holding, sellTrade2, new BigDecimal("3"), NOW);
		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo("4");
		assertThat(result2.totalAllocatedCost()).isEqualTo(99L);
		assertThat(result2.totalAllocatedBuyFee()).isEqualTo(30L);

		// 실제 저장소라면 이 시점까지 배분된 누적값을 SUM으로 돌려줄 것이다 — 앞의 두 실제 호출 결과를
		// 그대로 이어붙여 mock에 반영한다(손으로 계산한 값이 아니라 실제 호출 결과를 체이닝).
		long previousCost = result1.totalAllocatedCost() + result2.totalAllocatedCost();
		long previousBuyFee = result1.totalAllocatedBuyFee() + result2.totalAllocatedBuyFee();
		when(tradeAllocationRepository.sumAllocatedCostByHoldingLotId(1L)).thenReturn(previousCost);
		when(tradeAllocationRepository.sumAllocatedBuyFeeByHoldingLotId(1L)).thenReturn(previousBuyFee);

		// 3번째 매도: 4주 (lot 정확히 소진) — 누적 낙전이 이번 배분에 정확히 흡수되어야 한다
		Trade sellTrade3 = testTrade(account, instrument, OrderSide.SELL, new BigDecimal("40"),
			new BigDecimal("4"), 160L, 2L, NOW);
		SellAllocationDto result3 = service.applySellTrade(holding, sellTrade3, new BigDecimal("4"), NOW);

		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo("0");
		// 비례식이었다면 floor(33.33333333*4)=133, floor(100*4/10)=40이었겠지만, 마지막 배분이므로
		// "진짜 총액/총수수료 - 누적 배분값"으로 정확히 맞춰진다(133이 아니라 135, 40이 아니라 41).
		assertThat(result3.totalAllocatedCost()).isEqualTo(buyTrade.getAmount() - previousCost);
		assertThat(result3.totalAllocatedBuyFee()).isEqualTo(lot.getBuyFee() - previousBuyFee);

		// 3번의 배분을 모두 더하면 lot 단위로 buyTrade.amount·buyFee와 원 단위까지 정확히 일치해야 한다
		long totalCostAcrossThreeSells = result1.totalAllocatedCost() + result2.totalAllocatedCost()
			+ result3.totalAllocatedCost();
		long totalBuyFeeAcrossThreeSells = result1.totalAllocatedBuyFee() + result2.totalAllocatedBuyFee()
			+ result3.totalAllocatedBuyFee();
		assertThat(totalCostAcrossThreeSells).isEqualTo(buyTrade.getAmount());
		assertThat(totalBuyFeeAcrossThreeSells).isEqualTo(lot.getBuyFee());
	}

	private static Account testAccount() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", EARLIER);
		Account account = Account.create(user, com.finplay.api.account.domain.Market.STOCK, EARLIER);
		ReflectionTestUtils.setField(account, "id", 1L);
		return account;
	}

	private static Instrument testInstrument() {
		Instrument instrument = Instrument.create(
			com.finplay.api.market.domain.Market.STOCK,
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
			com.finplay.api.market.domain.StockReplaySession.ready(executedAt.toLocalDate(), executedAt.toLocalDate(),
				executedAt, executedAt),
			side, price, quantity,
			amount, fee, null, executedAt, executedAt);
	}
}
