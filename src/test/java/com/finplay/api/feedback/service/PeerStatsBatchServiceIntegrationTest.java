// 고정 Clock + Testcontainers로 장 마감 집단 비교 확정 집계 배치(PeerStatsBatchService)의 종단을 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.feedback.domain.PriceMovePeerStat;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.market.service.BusinessDayCalendar;
import com.finplay.api.market.service.StockReplayService;
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
import com.finplay.api.portfolio.service.HolderPopulationQueryService;
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

// tasks.md 3번 항목의 완료 조건 4개(저장·중복 없음·재재생 서비스 날짜 분리·READY 게이트) + 세 지표(모집단·매도비율·
// 중앙값)의 손 계산 대조와, tasks.md 6번 항목의 원장 불변(8개 이슈 공통 조건 중 이 배치가 맡는 절반)이 이 파일의
// 목표다. 매도 회고 조회 쪽 절반은 PostSellFeedbackNarrativeIntegrationTest가 맡는다.
//
// LLM은 부르지 않는다 — 이 배치는 애초에 새 LLM 호출을 추가하지 않는다(spec 012 이슈 #212 제약).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class PeerStatsBatchServiceIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// 원본 거래일 2026-08-05(수)가 서비스 날짜 2026-08-06(목)에 재생된다.
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	// 배치 실행 시각 (장 마감 15:30 직후).
	private static final LocalDateTime BATCH_AT = LocalDateTime.of(SERVICE_DATE, LocalTime.of(15, 32));

	// 카드 windowEnd. T = SERVICE_DATE + 09:10.
	private static final LocalTime WINDOW_END = LocalTime.of(9, 10);
	private static final LocalDateTime T = LocalDateTime.of(SERVICE_DATE, WINDOW_END);

	// 배치 실행 전후로 행이 변하면 안 되는 원장 테이블 (FeedbackBatchIntegrationTest와 같은 목록이다)
	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	// 이 배치가 읽기만 해야 하는 테이블 — 카드는 참조만 하고 갱신하지 않는다.
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

	// 전역 Clock 빈을 대신하는 공용 테스트 시계 (TestClockConfig). 기준 시각은 @BeforeEach에서 세운다.
	@Autowired
	private TestClock clock;

	private Instrument instrument;

	// 매수·매도 체결이 참조하는 더미 재생세션 — PeerStatsBatchService가 조회하는 "현재 재생세션"과는 무관하고
	// Trade.of의 FK 제약을 만족시키기 위한 값이다(HolderPopulationQueryServiceTest 선례). 실제 배치 대상 세션의
	// service_date와 겹치지 않게 멀리 둔다.
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

		// M1 10분 후 매도(30분 내), M2 정확히 30분 후 매도(경계, 30분 내로 포함), M3 45분 후(30분 밖),
		// M4 100분 후(30분 밖), M5 미매도(장 마감까지 안 팜) — holderCount=5, soldWithin30MinCount=2,
		// minutesToSell=[10,30,45,100] → median=(30+45)/2=37(정수 나눗셈).
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

	// 함정 재현 — 같은 원본 거래일이 두 서비스 날짜에 재생되면 집계가 서비스 날짜별로 따로 쌓인다
	// (UNIQUE(price_move_event_id)만 보는 잘못된 구현이면 이 테스트가 실패한다).
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

	// 8개 이슈 공통 조건(원장 불변, tasks.md 6번 항목) — 이 배치의 유일한 쓰기 대상은 price_move_peer_stats다.
	// 행 수만 보면 값이 바뀐 UPDATE(계좌 잔액·lot 잔여수량)를 놓치므로 값 비교를 더한다
	// (docs/agent-mistakes.md 2026-08-04 "원장 불변 행 수 스냅샷" 행).
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

		// 실제로 쓰기가 일어났는데도 나머지가 그대로여야 의미가 있다.
		assertThat(priceMovePeerStatRepository.count()).isGreaterThan(statsBefore);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
		assertThat(mutableLedgerValues()).isEqualTo(mutableLedgerBefore);
	}

	/**
	 * 원장에서 값이 바뀔 수 있는 자리 — 계좌 잔액과 lot 잔여수량이다. 행 수 비교로는 UPDATE가 잡히지 않는다.
	 *
	 * <p>{@code flush()}가 이 단정의 전제다 — 이 클래스는 트랜잭션 안에서 raw JDBC로 읽으므로 flush 없이는 보류된
	 * UPDATE가 보이지 않아 원장을 건드린 구현도 초록이 된다(PostSellFeedbackNarrativeIntegrationTest와 같은 패턴).
	 */
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

	// day의 15:32를 "현재 시각"으로 보는 StockReplayService·PeerStatsBatchService를 그 자리에서만 만들어 돌린다 —
	// 컨텍스트의 @Primary Clock 빈은 한 번 생성되면 고정이라 같은 테스트 안에서 서비스 날짜를 바꿔 재실행할 수
	// 없기 때문이다.
	private void runBatchAsOfServiceDate(LocalDate serviceDate) {
		Clock clockForDate = Clock.fixed(
			LocalDateTime.of(serviceDate, LocalTime.of(15, 32)).atZone(KST).toInstant(), KST);
		StockReplayService replayServiceForDate = new StockReplayService(
			stockReplaySessionRepository, stockCandleRepository, clockForDate, businessDayCalendar);
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

	// T 시점 이전에 사서, T + minutesAfterT 뒤에 파는 보유자 1명을 만든다.
	private void givenHolderWhoSellsAfter(int minutesAfterT) {
		Holding holding = createHolding();
		HoldingLot lot = createBuyLot(holding, BigDecimal.valueOf(10), T.minusHours(3));
		Trade sellTrade = createSellTrade(holding.getAccount(), BigDecimal.valueOf(10), T.plusMinutes(minutesAfterT));
		allocate(sellTrade, lot, BigDecimal.valueOf(10));
	}

	// T 시점 이전에 사서 계속 보유 중인(미매도) 보유자 1명을 만든다.
	private void givenHolderWhoNeverSells() {
		Holding holding = createHolding();
		createBuyLot(holding, BigDecimal.valueOf(10), T.minusHours(3));
	}

	private Holding createHolding() {
		memberSeq++;
		User user = userRepository.saveAndFlush(
			User.create("peer-trader" + memberSeq + "@finplay.com", "hash", "peer" + memberSeq, T));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.STOCK, T));
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
