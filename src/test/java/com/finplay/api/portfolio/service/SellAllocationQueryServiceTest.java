// 실제 MySQL 배분·lot 위에서 매도 회고용 배분 요약(가중평균 매수단가·가장 이른 매수 시각·lot별 원본 거래일)을 검증한다.
package com.finplay.api.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.domain.HoldingLot;
import com.finplay.api.portfolio.domain.TradeAllocation;
import com.finplay.api.portfolio.repository.HoldingLotRepository;
import com.finplay.api.portfolio.repository.HoldingRepository;
import com.finplay.api.portfolio.repository.TradeAllocationRepository;
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

// spec 012 §C-6이 portfolio에 요구한 "배분·lot 조회"다. 매도 회고의 buyAt(가장 이른 lot의 체결 시각)과
// sameSessionCompleted 판정 재료가 여기서 나오므로, 파인더의 정렬이 곧 응답 값이다 — mock으로는 "무슨 인자로
// 물었는가"까지만 볼 수 있어 실 DB로 본다(ADR-0003).
//
// SellAllocationQueryService는 @Service이지만 이 슬라이스가 올리지 않으므로 직접 생성한다
// (StockReplayServiceFullDayQueryTest 선례). @DataJpaTest가 이미 트랜잭션 안이라 서비스의
// @Transactional(readOnly = true)이 프록시 없이도 같은 조건이다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class SellAllocationQueryServiceTest {

	// 같은 원본 거래일을 여러 서비스 날짜에 재생할 수 있다 — 수집이 하루 실패하면 스케줄러가 과거로 거슬러
	// 올라간다(§C-2-1). 그래서 lot의 벽시계 날짜와 원본 거래일을 따로 세운다.
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate OTHER_ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 30);
	// 서비스 날짜는 stock_replay_sessions의 UNIQUE(service_date)에 걸린다. 공유 Testcontainer에 트랜잭션 없이
	// 커밋하는 테스트(CandleQueryServiceIntegrationTest가 2026-08-04·08-05를 커밋한다)와 같은 날짜를 쓰면
	// 단독 실행은 통과하고 `./gradlew build` 전체에서만 Duplicate entry로 깨진다 — 그래서 이 파일 전용
	// 연도(2031)를 쓴다. 원본 거래일은 UNIQUE 대상이 아니라 그대로 둔다.
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
			Account.create(user, com.finplay.api.account.domain.Market.STOCK, NOW));
		// V7 시드 심볼과 겹치지 않는 테스트 전용 심볼 — UNIQUE(symbol) 충돌 방지.
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST208", "테스트종목208", BigDecimal.valueOf(100), 10_000L, true, NOW));
		holding = holdingRepository.saveAndFlush(Holding.create(account, instrument, NOW));

		firstSession = saveSession(FIRST_SERVICE_DATE, ORIGIN_TRADE_DATE);
		secondSession = saveSession(SECOND_SERVICE_DATE, OTHER_ORIGIN_TRADE_DATE);
		sellSession = saveSession(SELL_SERVICE_DATE, ORIGIN_TRADE_DATE);
	}

	// --- 파인더 정렬 ---

	// 첫 행의 lot 체결시각이 곧 회고의 buyAt이므로 순서가 값이다. lot의 저장 순서(=PK 순서)를 체결시각 순서와
	// 어긋나게 만들어, executedAt 정렬을 잃은 구현이 실제로 빨개지는지 확인한다.
	//
	// 2차 키(lot.id)는 사정이 다르다 — 체결시각이 같은 두 lot에서 InnoDB가 흔히 PK 순서로 돌려주어 2차 키를
	// 지운 구현도 이 단정을 우연히 통과한다. 그래서 보호를 파인더 이름과 계약 문장 양쪽에 남긴다(tasks.md).
	@Test
	@DisplayName("배분은 lot 체결시각 오름차순 + lot id 오름차순으로 읽는다 — 저장 순서가 체결 순서와 달라도 그렇다")
	void readsAllocationsOrderedByLotExecutedAtThenLotId() {
		Trade sellTrade = saveSellTrade(new BigDecimal("10"));
		// 저장 순서를 일부러 뒤집는다 — 10:30 lot이 먼저 저장되어 더 작은 id를 갖는다.
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

	// --- 배분 요약 ---

	// 계약 예시(docs/api-contracts.md)를 두 lot으로 재현한다 — 완료 조건 2번이 2개 lot 픽스처를 요구한다.
	// 매수원가 합 700,000 / 수량 10 = 70,000이고 매수수수료 합은 105다.
	@Test
	@DisplayName("두 lot에 배분된 매도의 요약 — 가중평균 매수단가·가장 이른 매수 시각·lot별 원본 거래일")
	void summarizesTwoLotAllocationWithWeightedAveragePriceAndEarliestBuy() {
		Trade sellTrade = saveSellTrade(new BigDecimal("10"));
		// 나중 lot을 먼저 저장한다 — PK 순서가 체결시각 순서와 어긋나야 executedAt 정렬을 잃은 구현이 드러난다.
		HoldingLot later = saveLot(secondSession, LocalTime.of(10, 30), new BigDecimal("6"));
		HoldingLot earliest = saveLot(firstSession, LocalTime.of(9, 30), new BigDecimal("4"));
		saveAllocation(sellTrade, later, new BigDecimal("6"), 420_000L, 63L);
		saveAllocation(sellTrade, earliest, new BigDecimal("4"), 280_000L, 42L);

		SellAllocationSummaryDto summary = sellAllocationQueryService.getSellAllocationSummary(sellTrade.getId());

		// Holding.averagePrice와 같은 scale 8 HALF_UP 관례다.
		assertThat(summary.buyPrice()).isEqualTo(new BigDecimal("70000.00000000"));
		assertThat(summary.allocatedCost()).isEqualTo(700_000L);
		assertThat(summary.allocatedBuyFee()).isEqualTo(105L);
		assertThat(summary.allocatedQuantity()).isEqualByComparingTo("10");
		// 나중 lot(10:30)을 먼저 저장·배분했어도 가장 이른 lot이 잡힌다.
		assertThat(summary.earliestBuyAt()).isEqualTo(LocalDateTime.of(FIRST_SERVICE_DATE, LocalTime.of(9, 30)));
		assertThat(summary.earliestBuySourceTradingDate()).isEqualTo(ORIGIN_TRADE_DATE);
		// 배분 순서(= lot 체결시각 순서)를 유지하고 lot별 원본 거래일을 그대로 돌려준다 — 판정은 feedback이 한다.
		assertThat(summary.buySourceTradingDates()).containsExactly(ORIGIN_TRADE_DATE, OTHER_ORIGIN_TRADE_DATE);
	}

	@Test
	@DisplayName("가중평균 매수단가가 나누어떨어지지 않으면 scale 8 HALF_UP이다")
	void weightedAverageBuyPriceIsRoundedHalfUpAtScaleEight() {
		Trade sellTrade = saveSellTrade(new BigDecimal("3"));
		HoldingLot lot = saveLot(firstSession, LocalTime.of(9, 30), new BigDecimal("3"));
		saveAllocation(sellTrade, lot, new BigDecimal("3"), 100_000L, 15L);

		SellAllocationSummaryDto summary = sellAllocationQueryService.getSellAllocationSummary(sellTrade.getId());

		// 100,000 ÷ 3 = 33,333.333333333… → scale 8 HALF_UP.
		assertThat(summary.buyPrice()).isEqualTo(new BigDecimal("33333.33333333"));
	}

	// 배분 0건은 원장 불일치다 — 빈 요약을 돌려주면 매수단가가 0으로 나누어지거나 조용히 null이 된다.
	@Test
	@DisplayName("배분이 0건이면 빈 요약이 아니라 예외다")
	void throwsWhenSellTradeHasNoAllocation() {
		Trade sellTrade = saveSellTrade(new BigDecimal("10"));

		assertThatThrownBy(() -> sellAllocationQueryService.getSellAllocationSummary(sellTrade.getId()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(String.valueOf(sellTrade.getId()));
	}

	// --- 배분된 매수 체결 id·체결시각 (spec 012 §C-6 · 4차 §FEED-013 결정 4) ---

	// 순서가 곧 프롬프트의 일기 순서다 — 무너지면 서술의 시간 축(가장 이른 executed_at 기준)과 어긋난다.
	// 저장 순서(=PK 순서)를 체결시각 순서와 일부러 어긋내, 정렬을 잃은 구현이 실제로 빨개지게 한다.
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

		// PK 순서와 결과 순서가 실제로 다름을 먼저 확인한다 — 같으면 이 단정이 우연히 통과한다.
		assertThat(earliest.getBuyTrade().getId()).isGreaterThan(later.getBuyTrade().getId());
		assertThat(allocatedBuyTrades)
			.extracting(AllocatedBuyTradeDto::buyTradeId)
			.containsExactly(
				earliest.getBuyTrade().getId(), middle.getBuyTrade().getId(), later.getBuyTrade().getId());
		// 시각이 프롬프트의 일기 줄머리가 된다 — id만 맞고 시각이 어긋나면 서술이 조용히 오도된다.
		assertThat(allocatedBuyTrades)
			.extracting(AllocatedBuyTradeDto::executedAt)
			.containsExactly(earliest.getExecutedAt(), middle.getExecutedAt(), later.getExecutedAt());
	}

	// 중복이 실제로 들어오는 경로는 "한 매수 체결의 lot이 여럿"이 아니다 — holding_lots에 UNIQUE(buy_trade_id)
	// (V10 uk_holding_lots_buy_trade)가 걸려 있어 그쪽은 스키마가 이미 막는다. 열려 있는 쪽은
	// trade_allocations로, (sell_trade_id, holding_lot_id)에 UNIQUE가 없어 같은 lot이 한 매도에 두 번
	// 배분될 수 있다. 그 경로로 중복을 만들어 LinkedHashSet 제거가 실제로 걸리는지 본다.
	//
	// 중복 제거가 순서를 무너뜨려서도 안 된다 — 첫 등장(가장 이른 lot)의 자리를 지켜야 프롬프트의 일기 순서와
	// 서술의 시간 축이 같다(§FEED-013 결정 4).
	@Test
	@DisplayName("같은 lot이 한 매도에 두 번 배분돼도 매수 체결은 한 번만, 가장 이른 자리에 나온다")
	void deduplicatesBuyTradesWhileKeepingTheEarliestPosition() {
		Trade sellTrade = saveSellTrade(new BigDecimal("9"));
		HoldingLot earliest = saveLot(firstSession, LocalTime.of(9, 30), new BigDecimal("3"));
		HoldingLot later = saveLot(firstSession, LocalTime.of(11, 0), new BigDecimal("3"));
		saveAllocation(sellTrade, earliest, new BigDecimal("2"), 140_000L, 21L);
		saveAllocation(sellTrade, later, new BigDecimal("3"), 210_000L, 31L);
		// 같은 lot에 대한 두 번째 배분 — 스키마가 막지 않는다.
		saveAllocation(sellTrade, earliest, new BigDecimal("1"), 70_000L, 10L);

		assertThat(sellAllocationQueryService.getAllocatedBuyTrades(sellTrade.getId()))
			.containsExactly(
				new AllocatedBuyTradeDto(earliest.getBuyTrade().getId(), earliest.getExecutedAt()),
				new AllocatedBuyTradeDto(later.getBuyTrade().getId(), later.getExecutedAt()));
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

	// getSellAllocationSummary와 계약이 다르다 — 그쪽은 원장 불일치를 드러내려고 던지지만, 이 메서드는 그 요약이
	// 이미 성공한 뒤에만 불려 0건이 나올 수 없고, 나오더라도 서술 재료가 없는 것일 뿐 조회를 죽일 이유가 아니다.
	@Test
	@DisplayName("배분이 0건이면 예외가 아니라 빈 목록이다 — 요약 조회와 계약이 다르다")
	void returnsEmptyListInsteadOfThrowingWhenSellTradeHasNoAllocation() {
		Trade sellTrade = saveSellTrade(new BigDecimal("10"));

		assertThat(sellAllocationQueryService.getAllocatedBuyTrades(sellTrade.getId())).isEmpty();
	}

	// --- 픽스처 ---

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
