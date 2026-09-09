package com.finplay.api.domain.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.entity.TradeAllocation;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class SellAllocationQueryServiceTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate OTHER_ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 30);
	private static final LocalDate FIRST_SERVICE_DATE = LocalDate.of(2031, 8, 3);
	private static final LocalDate SECOND_SERVICE_DATE = LocalDate.of(2031, 8, 4);
	private static final LocalDate SELL_SERVICE_DATE = LocalDate.of(2031, 8, 5);

	private static final LocalDateTime NOW = LocalDateTime.of(SELL_SERVICE_DATE, LocalTime.of(14, 40));

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private HoldingRepository holdingRepository;

	@Autowired
	private HoldingLotRepository holdingLotRepository;

	@Autowired
	private TradeAllocationRepository tradeAllocationRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	private SellAllocationQueryService sellAllocationQueryService;

	private Account account;
	private Instrument instrument;
	private Holding holding;
	private StockReplaySession firstSession;
	private StockReplaySession secondSession;
	private StockReplaySession sellSession;

	@BeforeEach
	void setUp() {
		sellAllocationQueryService = new SellAllocationQueryService(tradeAllocationRepository);

		User user = userRepository.saveAndFlush(User.create("post-sell@finplay.com", "hash", "postsell", NOW));
		account = accountRepository.saveAndFlush(
			Account.create(user, Market.STOCK, NOW));
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST208", "테스트종목208", BigDecimal.valueOf(100), 10_000L, true, NOW));
		holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));

		firstSession = saveSession(FIRST_SERVICE_DATE, ORIGIN_TRADE_DATE);
		secondSession = saveSession(SECOND_SERVICE_DATE, OTHER_ORIGIN_TRADE_DATE);
		sellSession = saveSession(SELL_SERVICE_DATE, ORIGIN_TRADE_DATE);
	}

	@Test
	@DisplayName("배분은 lot 체결시각 오름차순 + lot id 오름차순으로 읽는다 — 저장 순서가 체결 순서와 달라도 그렇다")
	void readsAllocationsOrderedByLotExecutedAtThenLotId() {
		Trade sellTrade = saveSellTrade(new BigDecimal("10"));
		HoldingLot later = saveLot(firstSession, LocalTime.of(10, 30), new BigDecimal("2"));
		HoldingLot earliest = saveLot(firstSession, LocalTime.of(9, 30), new BigDecimal("2"));
		HoldingLot tieFirst = saveLot(firstSession, LocalTime.of(11, 0), new BigDecimal("3"));
		HoldingLot tieSecond = saveLot(firstSession, LocalTime.of(11, 0), new BigDecimal("3"));
		saveAllocation(sellTrade, tieSecond, new BigDecimal("3"), 210_000L, 31L);
		saveAllocation(sellTrade, later, new BigDecimal("2"), 140_000L, 21L);
		saveAllocation(sellTrade, tieFirst, new BigDecimal("3"), 210_000L, 31L);
		saveAllocation(sellTrade, earliest, new BigDecimal("2"), 140_000L, 21L);

		List<TradeAllocation> allocations = tradeAllocationRepository
			.findAllBySellTradeIdOrderByLotExecutedAtAscLotIdAsc(sellTrade.getId());

		assertThat(earliest.getId()).isGreaterThan(later.getId());
		assertThat(allocations)
			.extracting(allocation -> allocation.getHoldingLot().getExecutedAt(),
				allocation -> allocation.getHoldingLot().getId())
			.containsExactly(
				tuple(LocalDateTime.of(FIRST_SERVICE_DATE, LocalTime.of(9, 30)), earliest.getId()),
				tuple(LocalDateTime.of(FIRST_SERVICE_DATE, LocalTime.of(10, 30)), later.getId()),
				tuple(LocalDateTime.of(FIRST_SERVICE_DATE, LocalTime.of(11, 0)), tieFirst.getId()),
				tuple(LocalDateTime.of(FIRST_SERVICE_DATE, LocalTime.of(11, 0)), tieSecond.getId()));
	}

	@Test
	@DisplayName("다른 매도 체결의 배분은 섞이지 않는다")
	void readsOnlyTheRequestedSellTradesAllocations() {
		Trade sellTrade = saveSellTrade(new BigDecimal("4"));
		Trade otherSellTrade = saveSellTrade(new BigDecimal("6"));
		HoldingLot mine = saveLot(firstSession, LocalTime.of(9, 30), new BigDecimal("4"));
		HoldingLot other = saveLot(secondSession, LocalTime.of(9, 0), new BigDecimal("6"));
		saveAllocation(sellTrade, mine, new BigDecimal("4"), 280_000L, 42L);
		saveAllocation(otherSellTrade, other, new BigDecimal("6"), 999_999L, 999L);

		SellAllocationSummaryDto summary = sellAllocationQueryService.getSellAllocationSummary(sellTrade.getId());

		assertThat(summary.allocatedCost()).isEqualTo(280_000L);
		assertThat(summary.allocatedBuyFee()).isEqualTo(42L);
		assertThat(summary.buySourceTradingDates()).containsExactly(ORIGIN_TRADE_DATE);
		assertThat(summary.earliestBuyAt())
			.isEqualTo(LocalDateTime.of(FIRST_SERVICE_DATE, LocalTime.of(9, 30)));
	}

	@Test
	@DisplayName("두 lot에 배분된 매도의 요약 — 가중평균 매수단가·가장 이른 매수 시각·lot별 원본 거래일")
	void summarizesTwoLotAllocationWithWeightedAveragePriceAndEarliestBuy() {
		Trade sellTrade = saveSellTrade(new BigDecimal("10"));
		HoldingLot later = saveLot(secondSession, LocalTime.of(10, 30), new BigDecimal("6"));
		HoldingLot earliest = saveLot(firstSession, LocalTime.of(9, 30), new BigDecimal("4"));
		saveAllocation(sellTrade, later, new BigDecimal("6"), 420_000L, 63L);
		saveAllocation(sellTrade, earliest, new BigDecimal("4"), 280_000L, 42L);

		SellAllocationSummaryDto summary = sellAllocationQueryService.getSellAllocationSummary(sellTrade.getId());

		assertThat(summary.buyPrice()).isEqualTo(new BigDecimal("70000.00000000"));
		assertThat(summary.allocatedCost()).isEqualTo(700_000L);
		assertThat(summary.allocatedBuyFee()).isEqualTo(105L);
		assertThat(summary.allocatedQuantity()).isEqualByComparingTo("10");
		assertThat(summary.earliestBuyAt()).isEqualTo(LocalDateTime.of(FIRST_SERVICE_DATE, LocalTime.of(9, 30)));
		assertThat(summary.earliestBuySourceTradingDate()).isEqualTo(ORIGIN_TRADE_DATE);
		assertThat(summary.buySourceTradingDates()).containsExactly(ORIGIN_TRADE_DATE, OTHER_ORIGIN_TRADE_DATE);
	}

	@Test
	@DisplayName("가중평균 매수단가가 나누어떨어지지 않으면 scale 8 HALF_UP이다")
	void weightedAverageBuyPriceIsRoundedHalfUpAtScaleEight() {
		Trade sellTrade = saveSellTrade(new BigDecimal("3"));
		HoldingLot lot = saveLot(firstSession, LocalTime.of(9, 30), new BigDecimal("3"));
		saveAllocation(sellTrade, lot, new BigDecimal("3"), 100_000L, 15L);

		SellAllocationSummaryDto summary = sellAllocationQueryService.getSellAllocationSummary(sellTrade.getId());

		assertThat(summary.buyPrice()).isEqualTo(new BigDecimal("33333.33333333"));
	}

	@Test
	@DisplayName("배분이 0건이면 빈 요약이 아니라 예외다")
	void throwsWhenSellTradeHasNoAllocation() {
		Trade sellTrade = saveSellTrade(new BigDecimal("10"));

		assertThatThrownBy(() -> sellAllocationQueryService.getSellAllocationSummary(sellTrade.getId()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(String.valueOf(sellTrade.getId()));
	}

	@Test
	@DisplayName("배분된 매수 체결을 매수 시각 오름차순으로 돌려준다 — 저장 순서가 체결 순서와 달라도 그렇다")
	void returnsAllocatedBuyTradesOrderedByBuyExecutedAt() {
		Trade sellTrade = saveSellTrade(new BigDecimal("9"));
		HoldingLot later = saveLot(firstSession, LocalTime.of(11, 0), new BigDecimal("3"));
		HoldingLot earliest = saveLot(firstSession, LocalTime.of(9, 30), new BigDecimal("3"));
		HoldingLot middle = saveLot(firstSession, LocalTime.of(10, 30), new BigDecimal("3"));
		saveAllocation(sellTrade, later, new BigDecimal("3"), 210_000L, 31L);
		saveAllocation(sellTrade, middle, new BigDecimal("3"), 210_000L, 31L);
		saveAllocation(sellTrade, earliest, new BigDecimal("3"), 210_000L, 31L);

		List<AllocatedBuyTradeDto> allocatedBuyTrades = sellAllocationQueryService
			.getAllocatedBuyTrades(sellTrade.getId());

		assertThat(earliest.getBuyTrade().getId()).isGreaterThan(later.getBuyTrade().getId());
		assertThat(allocatedBuyTrades)
			.extracting(AllocatedBuyTradeDto::buyTradeId)
			.containsExactly(
				earliest.getBuyTrade().getId(), middle.getBuyTrade().getId(), later.getBuyTrade().getId());
		assertThat(allocatedBuyTrades)
			.extracting(AllocatedBuyTradeDto::executedAt)
			.containsExactly(earliest.getExecutedAt(), middle.getExecutedAt(), later.getExecutedAt());
	}

	@Test
	@DisplayName("다른 매도 체결에 배분된 매수 체결은 섞이지 않는다")
	void returnsOnlyTheRequestedSellTradesBuyTrades() {
		Trade sellTrade = saveSellTrade(new BigDecimal("3"));
		Trade otherSellTrade = saveSellTrade(new BigDecimal("3"));
		HoldingLot mine = saveLot(firstSession, LocalTime.of(9, 30), new BigDecimal("3"));
		HoldingLot other = saveLot(secondSession, LocalTime.of(9, 0), new BigDecimal("3"));
		saveAllocation(sellTrade, mine, new BigDecimal("3"), 210_000L, 31L);
		saveAllocation(otherSellTrade, other, new BigDecimal("3"), 210_000L, 31L);

		assertThat(sellAllocationQueryService.getAllocatedBuyTrades(sellTrade.getId()))
			.containsExactly(new AllocatedBuyTradeDto(mine.getBuyTrade().getId(), mine.getExecutedAt()));
	}

	@Test
	@DisplayName("배분이 0건이면 예외가 아니라 빈 목록이다 — 요약 조회와 계약이 다르다")
	void returnsEmptyListInsteadOfThrowingWhenSellTradeHasNoAllocation() {
		Trade sellTrade = saveSellTrade(new BigDecimal("10"));

		assertThat(sellAllocationQueryService.getAllocatedBuyTrades(sellTrade.getId())).isEmpty();
	}

	private StockReplaySession saveSession(LocalDate serviceDate, LocalDate sourceTradingDate) {
		LocalDateTime resolvedAt = LocalDateTime.of(serviceDate, LocalTime.of(8, 40));
		return stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(serviceDate, sourceTradingDate, resolvedAt, resolvedAt));
	}

	private HoldingLot saveLot(StockReplaySession session, LocalTime executedTime, BigDecimal quantity) {
		LocalDateTime executedAt = LocalDateTime.of(session.getServiceDate(), executedTime);
		Trade buyTrade = saveTrade(session, OrderSide.BUY, quantity, new BigDecimal("70000"), null, executedAt);
		return holdingLotRepository.saveAndFlush(
			HoldingLot.create(holding, buyTrade, quantity, new BigDecimal("70000"), 100L, executedAt, executedAt));
	}

	private Trade saveSellTrade(BigDecimal quantity) {
		LocalDateTime executedAt = LocalDateTime.of(SELL_SERVICE_DATE, LocalTime.of(14, 40));
		return saveTrade(sellSession, OrderSide.SELL, quantity, new BigDecimal("68500"), -15_207L, executedAt);
	}

	private Trade saveTrade(
		StockReplaySession session,
		OrderSide side,
		BigDecimal quantity,
		BigDecimal price,
		Long realizedPnl,
		LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			account.getUser(), account, instrument, side, OrderType.MARKET, quantity,
			"idem-" + System.nanoTime(), "a".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, instrument, session, side, price, quantity,
			price.multiply(quantity).longValueExact(), 102L, realizedPnl, executedAt, executedAt));
	}

	private void saveAllocation(
		Trade sellTrade, HoldingLot lot, BigDecimal quantity, long allocatedCost, long allocatedBuyFee) {
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sellTrade, lot, quantity, allocatedCost, allocatedBuyFee, NOW));
	}
}
