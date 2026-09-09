package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.entity.PriceMovePeerStat;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.feedback.repository.TradeFeedbackRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, PostSellFeedbackNarrativeIntegrationTest.NarrativeTestConfig.class})
class PostSellFeedbackNarrativeIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate OTHER_ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 30);
	private static final LocalDate TRADE_SERVICE_DATE = LocalDate.of(2031, 8, 4);
	private static final LocalDate EARLIER_SERVICE_DATE = LocalDate.of(2031, 8, 3);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);

	private static final LocalDateTime VIEW_AT = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(16, 0));

	private static final String LLM_NARRATIVE = "09시 30분에 70,000원에 매수한 뒤 14시 40분에 68,500원에 매도했습니다.";

	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	private static final List<String> READ_ONLY_TABLES = List.of("instruments", "stock_candles",
		"stock_replay_sessions", "market_news_items");

	private static final List<String> OTHER_FEEDBACK_TABLES = List.of("price_move_events",
		"price_move_event_sources", "instrument_news_summaries", "market_briefings", "price_move_peer_stats");

	@Autowired
	private PostSellFeedbackService postSellFeedbackService;

	@Autowired
	private FakeNarrativeGenerator fakeNarrativeGenerator;

	@MockitoSpyBean
	private TradeFeedbackRepository tradeFeedbackRepository;

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

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceMovePeerStatRepository priceMovePeerStatRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	private User owner;
	private Account account;
	private Instrument stock;
	private Holding holding;
	private StockReplaySession tradeSession;
	private Trade sellTrade;

	@BeforeEach
	void setUp() {
		fakeNarrativeGenerator.reset();

		owner = userRepository.saveAndFlush(User.create("post-sell-narrative@finplay.com", "hash", "narr208", VIEW_AT));
		account = accountRepository.saveAndFlush(
			Account.create(owner, Market.STOCK, VIEW_AT));
		stock = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST208N", "테스트종목208N", BigDecimal.valueOf(100), 10_000L, true,
				VIEW_AT));
		holding = holdingRepository.saveAndFlush(Holding.create(account, stock, VIEW_AT));
		tradeSession = saveSession(TRADE_SERVICE_DATE, ORIGIN_TRADE_DATE);

		saveCandle(BUY_TIME, "69500");
		saveCandle(LocalTime.of(11, 5), "70800");
		saveCandle(LocalTime.of(14, 20), "68100");
		saveCandle(SELL_TIME, "68500");
		saveCandle(LocalTime.of(15, 5), "69500");
		saveCandle(LAST_CANDLE_TIME, "69200");

		sellTrade = saveSellTrade(tradeSession);
		allocate(sellTrade, saveLot(tradeSession, BUY_TIME));
	}


	@Test
	@DisplayName("최초 조회에서 서술을 만들어 저장하고 재조회에서는 LLM을 다시 부르지 않는다")
	void generatesOnFirstQueryAndReusesTheStoredNarrativeAfterwards() {
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock, PriceMoveEventType.INTRADAY, ORIGIN_TRADE_DATE, LocalTime.of(9, 45), LocalTime.of(9, 50),
			new BigDecimal("-0.018200"), new BigDecimal("3.2500"), "09시 45분부터 하락했습니다.",
			NarrativeSource.LLM, LocalTime.of(9, 51), VIEW_AT));
		fakeNarrativeGenerator.enqueue(LLM_NARRATIVE);

		PostSellFeedbackResponse first = getPostSellFeedback();
		assertThat(first.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		PostSellFeedbackResponse second = getPostSellFeedback();

		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(1);
		assertThat(first.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(first.narrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(first.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(second.narrative()).isEqualTo(first.narrative());
		assertThat(second.narrativeSource()).isEqualTo(first.narrativeSource());
		assertThat(feedbackRowCount()).isEqualTo(1L);
		assertThat(tradeFeedbackRepository.findByTradeId(sellTrade.getId()))
			.get()
			.satisfies(feedback -> {
				assertThat(feedback.getNarrative()).isEqualTo(LLM_NARRATIVE);
				assertThat(feedback.getNarrativeSource()).isEqualTo(NarrativeSource.LLM);
				assertThat(feedback.isNarrativeFinalized()).isFalse();
				assertThat(feedback.getRegenerationAttempts()).isZero();
				assertThat(feedback.getGeneratedAt()).isEqualTo(VIEW_AT);
			});
	}


	@Test
	@DisplayName("LLM이 실패하면 템플릿 문장으로 채워지고 narrativeStatus는 READY·source는 TEMPLATE이다")
	void fallsBackToTheTemplateSentenceWhenTheGeneratorFails() {
		fakeNarrativeGenerator.enqueueFailure();

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(1);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(response.narrative()).isNotBlank();
		assertThat(response.narrative()).contains("68,500");
		assertThat(tradeFeedbackRepository.findByTradeId(sellTrade.getId()))
			.get()
			.satisfies(feedback -> assertThat(feedback.getNarrativeSource()).isEqualTo(NarrativeSource.TEMPLATE));
	}

	@Test
	@DisplayName("LLM이 실패해도 수치 요약·파생 사실·매도 후 흐름이 그대로 나온다")
	void keepsEveryNumberAndDerivedFactWhenTheGeneratorFails() {
		fakeNarrativeGenerator.enqueueFailure();

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.buyPrice()).isEqualByComparingTo("70000");
		assertThat(response.sellPrice()).isEqualByComparingTo("68500");
		assertThat(response.returnRate()).isEqualTo(new BigDecimal("-0.0217"));
		assertThat(response.holdingMinutes()).isEqualTo(310);
		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdLowPrice()).isEqualByComparingTo("68100");
		assertThat(response.sellVsHighRate()).isEqualTo(new BigDecimal("-0.0325"));
		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("69200");
		assertThat(response.counterfactuals().atClose().price()).isEqualByComparingTo("69200");
	}

	@Test
	@DisplayName("sameSessionCompleted=false여도 서술이 비지 않고 READY가 유지된다")
	void keepsTheNarrativeNonEmptyWhenTheTradeSpansMultipleOriginTradeDates() {
		fakeNarrativeGenerator.enqueueFailure();
		StockReplaySession otherSession = saveSession(EARLIER_SERVICE_DATE, OTHER_ORIGIN_TRADE_DATE);
		Trade crossSessionSell = saveSellTrade(tradeSession);
		allocate(crossSessionSell, saveLot(otherSession, BUY_TIME));

		PostSellFeedbackResponse response = postSellFeedbackService.getPostSellFeedback(
			owner.getId(), crossSessionSell.getId());

		assertThat(response.sameSessionCompleted()).isFalse();
		assertThat(response.holdHighPrice()).isNull();
		assertThat(response.postSellFlow()).isNull();
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.narrative()).isNotBlank();
		assertThat(response.narrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
	}


	@Test
	@DisplayName("peerComparison이 READY인 조회도 trade_feedbacks에만 1행을 쓰고 나머지는 그대로다")
	void neverWritesOutsideTradeFeedbacksWhenPeerComparisonIsReady() {
		fakeNarrativeGenerator.enqueue(LLM_NARRATIVE);
		PriceMoveEvent card = priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock, PriceMoveEventType.INTRADAY, ORIGIN_TRADE_DATE, LocalTime.of(9, 45), LocalTime.of(9, 50),
			new BigDecimal("-0.018200"), new BigDecimal("3.2500"), "09시 45분부터 하락했습니다.",
			NarrativeSource.LLM, LocalTime.of(9, 51), VIEW_AT));
		priceMovePeerStatRepository.saveAndFlush(
			PriceMovePeerStat.create(card, TRADE_SERVICE_DATE, 5, 2, 20, VIEW_AT));

		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		Map<String, Long> readOnlyBefore = rowCounts(READ_ONLY_TABLES);
		Map<String, Long> otherFeedbackBefore = rowCounts(OTHER_FEEDBACK_TABLES);
		List<Map<String, Object>> mutableLedgerBefore = mutableLedgerValues();
		long feedbacksBefore = feedbackRowCount();

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.peerComparison().holderCount()).isEqualTo(5);
		assertThat(feedbackRowCount()).isEqualTo(feedbacksBefore + 1);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
		assertThat(rowCounts(OTHER_FEEDBACK_TABLES)).isEqualTo(otherFeedbackBefore);
		assertThat(mutableLedgerValues()).isEqualTo(mutableLedgerBefore);
	}

	@Test
	@DisplayName("회고 조회는 trade_feedbacks에만 1행을 쓰고 원장·읽기 전용·다른 피드백 테이블은 그대로다")
	void neverWritesOutsideTradeFeedbacks() {
		fakeNarrativeGenerator.enqueue(LLM_NARRATIVE);
		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		Map<String, Long> readOnlyBefore = rowCounts(READ_ONLY_TABLES);
		Map<String, Long> otherFeedbackBefore = rowCounts(OTHER_FEEDBACK_TABLES);
		Map<String, Object> tradeRowBefore = tradeRow();
		List<Map<String, Object>> mutableLedgerBefore = mutableLedgerValues();
		long feedbacksBefore = feedbackRowCount();

		getPostSellFeedback();

		assertThat(feedbackRowCount()).isEqualTo(feedbacksBefore + 1);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
		assertThat(rowCounts(OTHER_FEEDBACK_TABLES)).isEqualTo(otherFeedbackBefore);
		assertThat(tradeRow()).isEqualTo(tradeRowBefore);
		assertThat(mutableLedgerValues()).isEqualTo(mutableLedgerBefore);
	}


	@Test
	@DisplayName("기존 행을 못 본 상태로 두 번째 저장이 시도돼도 예외 없이 서술이 내려간다")
	void absorbsTheUniqueViolationWhenTheExistingRowIsNotSeen() {
		fakeNarrativeGenerator.enqueue(LLM_NARRATIVE).enqueue(LLM_NARRATIVE);
		getPostSellFeedback();
		assertThat(feedbackRowCount()).isEqualTo(1L);
		doReturn(Optional.empty()).when(tradeFeedbackRepository).findByTradeId(sellTrade.getId());

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.narrative()).isEqualTo(LLM_NARRATIVE);
		assertThat(response.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(2);
	}


	private PostSellFeedbackResponse getPostSellFeedback() {
		return postSellFeedbackService.getPostSellFeedback(owner.getId(), sellTrade.getId());
	}

	private long feedbackRowCount() {
		return jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM trade_feedbacks WHERE trade_id = ?", Long.class, sellTrade.getId());
	}

	private List<Map<String, Object>> mutableLedgerValues() {
		entityManager.flush();
		List<Map<String, Object>> rows = new ArrayList<>(
			jdbcTemplate.queryForList("SELECT id, cash_balance FROM accounts ORDER BY id"));
		rows.addAll(jdbcTemplate.queryForList(
			"SELECT id, remaining_quantity FROM holding_lots ORDER BY id"));
		return rows;
	}

	private Map<String, Object> tradeRow() {
		entityManager.flush();
		return jdbcTemplate.queryForMap("SELECT * FROM trades WHERE id = ?", sellTrade.getId());
	}

	private Map<String, Long> rowCounts(List<String> tables) {
		entityManager.flush();
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : tables) {
			counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		return counts;
	}

	private StockReplaySession saveSession(LocalDate serviceDate, LocalDate sourceTradingDate) {
		LocalDateTime resolvedAt = LocalDateTime.of(serviceDate, LocalTime.of(8, 40));
		return stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(serviceDate, sourceTradingDate, resolvedAt, resolvedAt));
	}

	private void saveCandle(LocalTime candleTime, String close) {
		BigDecimal price = new BigDecimal(close);
		stockCandleRepository.saveAndFlush(StockCandle.create(
			stock, ORIGIN_TRADE_DATE, candleTime, price, price.add(new BigDecimal("300")),
			price.subtract(new BigDecimal("300")), price, 1_000L, "TEST", VIEW_AT));
	}

	private HoldingLot saveLot(StockReplaySession session, LocalTime executedTime) {
		LocalDateTime executedAt = LocalDateTime.of(session.getServiceDate(), executedTime);
		Trade buyTrade = saveTrade(
			session, OrderSide.BUY, new BigDecimal("10"), new BigDecimal("70000"), null, executedAt);
		return holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, new BigDecimal("10"), new BigDecimal("70000"), 105L, executedAt, VIEW_AT));
	}

	private Trade saveSellTrade(StockReplaySession session) {
		return saveTrade(
			session, OrderSide.SELL, new BigDecimal("10"), new BigDecimal("68500"), -15_207L,
			LocalDateTime.of(session.getServiceDate(), SELL_TIME));
	}

	private void allocate(Trade sell, HoldingLot lot) {
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sell, lot, new BigDecimal("10"), 700_000L, 105L, VIEW_AT));
	}

	private Trade saveTrade(
		StockReplaySession session,
		OrderSide side,
		BigDecimal quantity,
		BigDecimal price,
		Long realizedPnl,
		LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, account, stock, side, OrderType.MARKET, quantity,
			"idem-" + System.nanoTime(), "a".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, stock, session, side, price, quantity,
			price.multiply(quantity).longValueExact(), 102L, realizedPnl, executedAt, executedAt));
	}

	@TestConfiguration
	static class NarrativeTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(VIEW_AT.atZone(KST).toInstant(), KST);
		}

		@Bean
		@Primary
		FakeNarrativeGenerator fakeNarrativeGenerator() {
			return new FakeNarrativeGenerator();
		}
	}
}
