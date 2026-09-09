package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.entity.PriceMovePeerStat;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockDailyCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import com.finplay.api.domain.market.service.StockReplayService;
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
import com.finplay.api.domain.portfolio.service.HolderPopulationQueryService;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class PeerStatsBatchServiceIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final LocalDateTime BATCH_AT = LocalDateTime.of(SERVICE_DATE, LocalTime.of(15, 32));

	private static final LocalTime WINDOW_END = LocalTime.of(9, 10);
	private static final LocalDateTime T = LocalDateTime.of(SERVICE_DATE, WINDOW_END);

	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	private static final List<String> READ_ONLY_TABLES = List.of("instruments", "price_move_events",
		"stock_replay_sessions");

	@Autowired
	private PeerStatsBatchService peerStatsBatchService;

	@Autowired
	private StockReplayService stockReplayService;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private StockDailyCandleRepository stockDailyCandleRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

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
	private HolderPopulationQueryService holderPopulationQueryService;

	@Autowired
	private BusinessDayCalendar businessDayCalendar;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceMovePeerStatRepository priceMovePeerStatRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private TestClock clock;

	private Instrument instrument;

	private StockReplaySession tradeLinkSession;

	private int memberSeq = 0;

	@BeforeEach
	void setUp() {
		clock.set(BATCH_AT);
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST12", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, T));
		tradeLinkSession = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(T.toLocalDate().plusYears(30), ORIGIN_TRADE_DATE, T, T));
	}

	@Test
	@DisplayName("모집단·30분 내 매도 수·매도까지 걸린 시간의 중앙값이 손으로 계산한 값과 일치하는 확정 집계가 저장된다")
	void storesConfirmedAggregateWithHandComputedMetrics() {
		givenReadySession(SERVICE_DATE, ORIGIN_TRADE_DATE);
		PriceMoveEvent card = givenCard();

		givenHolderWhoSellsAfter(10);
		givenHolderWhoSellsAfter(30);
		givenHolderWhoSellsAfter(45);
		givenHolderWhoSellsAfter(100);
		givenHolderWhoNeverSells();

		peerStatsBatchService.runPeerStatsBatch();

		List<PriceMovePeerStat> stats = priceMovePeerStatRepository.findAll();
		assertThat(stats).hasSize(1);
		PriceMovePeerStat stat = stats.get(0);
		assertThat(stat.getPriceMoveEvent().getId()).isEqualTo(card.getId());
		assertThat(stat.getServiceDate()).isEqualTo(SERVICE_DATE);
		assertThat(stat.getHolderCount()).isEqualTo(5);
		assertThat(stat.getSoldWithin30MinCount()).isEqualTo(2);
		assertThat(stat.getMedianMinutesToSell()).isEqualTo(37);
	}

	@Test
	@DisplayName("보유자 전원이 미매도면 medianMinutesToSell이 0이 아니라 null로 저장된다")
	void storesNullMedianWhenNoHolderSells() {
		givenReadySession(SERVICE_DATE, ORIGIN_TRADE_DATE);
		givenCard();

		givenHolderWhoNeverSells();
		givenHolderWhoNeverSells();

		peerStatsBatchService.runPeerStatsBatch();

		PriceMovePeerStat stat = priceMovePeerStatRepository.findAll().get(0);
		assertThat(stat.getHolderCount()).isEqualTo(2);
		assertThat(stat.getSoldWithin30MinCount()).isZero();
		assertThat(stat.getMedianMinutesToSell()).isNull();
	}

	@Test
	@DisplayName("재생세션이 READY가 아니면 배치가 확정 집계를 하나도 만들지 않는다")
	void doesNothingWhenReplaySessionIsNotReady() {
		stockReplaySessionRepository.save(
			StockReplaySession.preparing(SERVICE_DATE, ORIGIN_TRADE_DATE, T));
		givenCard();
		givenHolderWhoSellsAfter(10);

		peerStatsBatchService.runPeerStatsBatch();

		assertThat(priceMovePeerStatRepository.findAll()).isEmpty();
	}

	@Test
	@DisplayName("같은 서비스 날짜에 배치를 두 번 실행해도 확정 집계가 중복 생성되지 않는다")
	void doesNotDuplicateWhenRunTwiceOnSameServiceDate() {
		givenReadySession(SERVICE_DATE, ORIGIN_TRADE_DATE);
		PriceMoveEvent card = givenCard();
		givenHolderWhoSellsAfter(10);

		peerStatsBatchService.runPeerStatsBatch();
		List<Long> firstRunIds = priceMovePeerStatRepository.findAll().stream().map(PriceMovePeerStat::getId).toList();
		assertThat(firstRunIds).hasSize(1);

		peerStatsBatchService.runPeerStatsBatch();

		List<PriceMovePeerStat> afterSecondRun = priceMovePeerStatRepository.findAll();
		assertThat(afterSecondRun).extracting(PriceMovePeerStat::getId).isEqualTo(firstRunIds);
		assertThat(afterSecondRun.get(0).getPriceMoveEvent().getId()).isEqualTo(card.getId());
	}

	@Test
	@DisplayName("같은 원본 거래일을 두 서비스 날짜에 재생하면 집계 행이 각 서비스 날짜에 따로 쌓인다")
	void createsSeparateAggregatesForEachServiceDateOnReplay() {
		LocalDate day1 = SERVICE_DATE;
		LocalDate day2 = SERVICE_DATE.plusDays(1);

		PriceMoveEvent card = givenCard();
		givenHolderWhoSellsAfter(10);

		givenReadySession(day1, ORIGIN_TRADE_DATE);
		runBatchAsOfServiceDate(day1);

		givenReadySession(day2, ORIGIN_TRADE_DATE);
		runBatchAsOfServiceDate(day2);

		List<PriceMovePeerStat> stats = priceMovePeerStatRepository.findAll().stream()
			.filter(stat -> stat.getPriceMoveEvent().getId().equals(card.getId()))
			.toList();

		assertThat(stats).hasSize(2);
		assertThat(stats).extracting(PriceMovePeerStat::getServiceDate)
			.containsExactlyInAnyOrder(day1, day2);
	}

	@Test
	@DisplayName("배치가 price_move_peer_stats에만 쓰고 원장·읽기 전용 테이블은 그대로다")
	void neverWritesOutsideThePriceMovePeerStatsTable() {
		givenReadySession(SERVICE_DATE, ORIGIN_TRADE_DATE);
		givenCard();
		givenHolderWhoSellsAfter(10);
		givenHolderWhoNeverSells();

		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		Map<String, Long> readOnlyBefore = rowCounts(READ_ONLY_TABLES);
		List<Map<String, Object>> mutableLedgerBefore = mutableLedgerValues();
		long statsBefore = priceMovePeerStatRepository.count();

		peerStatsBatchService.runPeerStatsBatch();

		assertThat(priceMovePeerStatRepository.count()).isGreaterThan(statsBefore);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
		assertThat(mutableLedgerValues()).isEqualTo(mutableLedgerBefore);
	}

	private List<Map<String, Object>> mutableLedgerValues() {
		entityManager.flush();
		List<Map<String, Object>> rows = new ArrayList<>(
			jdbcTemplate.queryForList("SELECT id, cash_balance FROM accounts ORDER BY id"));
		rows.addAll(jdbcTemplate.queryForList("SELECT id, remaining_quantity FROM holding_lots ORDER BY id"));
		return rows;
	}

	private Map<String, Long> rowCounts(List<String> tables) {
		entityManager.flush();
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : tables) {
			counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		return counts;
	}

	private void runBatchAsOfServiceDate(LocalDate serviceDate) {
		Clock clockForDate = Clock.fixed(
			LocalDateTime.of(serviceDate, LocalTime.of(15, 32)).atZone(KST).toInstant(), KST);
		StockReplayService replayServiceForDate = new StockReplayService(
			stockReplaySessionRepository, stockCandleRepository, stockDailyCandleRepository, clockForDate,
			businessDayCalendar);
		PeerStatsBatchService batchForDate = new PeerStatsBatchService(
			replayServiceForDate, priceMoveEventRepository, priceMovePeerStatRepository,
			holderPopulationQueryService, clockForDate);
		batchForDate.runPeerStatsBatch();
	}

	private void givenReadySession(LocalDate serviceDate, LocalDate originTradeDate) {
		stockReplaySessionRepository.save(
			StockReplaySession.ready(serviceDate, originTradeDate, T, T));
	}

	private PriceMoveEvent givenCard() {
		return priceMoveEventRepository.save(PriceMoveEvent.createStock(
			instrument, PriceMoveEventType.INTRADAY, ORIGIN_TRADE_DATE,
			WINDOW_END.minusMinutes(5), WINDOW_END,
			BigDecimal.valueOf(0.01), BigDecimal.valueOf(3.0),
			"테스트 카드", NarrativeSource.TEMPLATE, LocalTime.of(9, 30), T));
	}

	private void givenHolderWhoSellsAfter(int minutesAfterT) {
		Holding holding = createHolding();
		HoldingLot lot = createBuyLot(holding, BigDecimal.valueOf(10), T.minusHours(3));
		Trade sellTrade = createSellTrade(holding.getAccount(), BigDecimal.valueOf(10), T.plusMinutes(minutesAfterT));
		allocate(sellTrade, lot, BigDecimal.valueOf(10));
	}

	private void givenHolderWhoNeverSells() {
		Holding holding = createHolding();
		createBuyLot(holding, BigDecimal.valueOf(10), T.minusHours(3));
	}

	private Holding createHolding() {
		memberSeq++;
		User user = userRepository.saveAndFlush(
			User.create("peer-trader" + memberSeq + "@finplay.com", "hash", "peer" + memberSeq, T));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.STOCK, T));
		return holdingRepository.saveAndFlush(Holding.create(account, instrument, T));
	}

	private HoldingLot createBuyLot(Holding holding, BigDecimal quantity, LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			holding.getAccount().getUser(), holding.getAccount(), instrument, OrderSide.BUY, OrderType.MARKET,
			quantity, "idem-buy-" + System.nanoTime(), "a".repeat(64), executedAt));
		Trade buyTrade = tradeRepository.saveAndFlush(Trade.of(
			order, holding.getAccount(), instrument, tradeLinkSession, OrderSide.BUY,
			BigDecimal.valueOf(70000), quantity,
			70000L * quantity.longValueExact(), 100L, null, executedAt, executedAt));
		return holdingLotRepository.saveAndFlush(
			HoldingLot.create(holding, buyTrade, quantity, BigDecimal.valueOf(70000), 100L, executedAt, executedAt));
	}

	private Trade createSellTrade(Account account, BigDecimal quantity, LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			account.getUser(), account, instrument, OrderSide.SELL, OrderType.MARKET,
			quantity, "idem-sell-" + System.nanoTime(), "b".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, instrument, tradeLinkSession, OrderSide.SELL,
			BigDecimal.valueOf(75000), quantity,
			75000L * quantity.longValueExact(), 100L, 49_900L, executedAt, executedAt));
	}

	private void allocate(Trade sellTrade, HoldingLot lot, BigDecimal quantity) {
		lot.consume(quantity);
		holdingLotRepository.saveAndFlush(lot);
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sellTrade, lot, quantity, 700_000L, 100L, sellTrade.getExecutedAt()));
	}

}
