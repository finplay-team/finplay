package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostSellFeedbackBoundaryIntegrationTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2031, 8, 6);

	private static final String SYMBOL = "TEST208B";
	private static final String EMAIL = "post-sell-boundary@finplay.com";

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30, 17, 400_000_000);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40, 5);
	private static final LocalDateTime NOW = LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 0));

	@Autowired
	private PostSellFeedbackService postSellFeedbackService;

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
	private JdbcTemplate jdbcTemplate;

	private Long userId;
	private Long sellTradeId;

	@BeforeEach
	void setUp() {
		User owner = userRepository.saveAndFlush(User.create(EMAIL, "hash", "boundary208", NOW));
		userId = owner.getId();
		Account account = accountRepository.saveAndFlush(
			Account.create(owner, Market.STOCK, NOW));
		Instrument stock = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, SYMBOL, "테스트종목208B", BigDecimal.valueOf(100), 10_000L, true, NOW));
		LocalDateTime resolvedAt = LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 40));
		StockReplaySession session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(SERVICE_DATE, ORIGIN_TRADE_DATE, resolvedAt, resolvedAt));
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, stock, NOW));

		saveCandle(stock, LocalTime.of(9, 30), "69500");
		saveCandle(stock, LocalTime.of(11, 5), "70800");
		saveCandle(stock, LocalTime.of(14, 40), "68500");

		Trade buyTrade = saveTrade(
			account, stock, session, OrderSide.BUY, new BigDecimal("70000"), null,
			LocalDateTime.of(SERVICE_DATE, BUY_TIME));
		HoldingLot lot = holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, new BigDecimal("10"), new BigDecimal("70000"), 105L,
			LocalDateTime.of(SERVICE_DATE, BUY_TIME), NOW));
		Trade sellTrade = saveTrade(
			account, stock, session, OrderSide.SELL, new BigDecimal("68500"), -15_207L,
			LocalDateTime.of(SERVICE_DATE, SELL_TIME));
		sellTradeId = sellTrade.getId();
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sellTrade, lot, new BigDecimal("10"), 700_000L, 105L, NOW));
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.update("delete from trade_feedbacks where trade_id in "
			+ "(select id from trades where instrument_id in (select id from instruments where symbol = ?))", SYMBOL);
		jdbcTemplate.update("delete from trade_allocations where sell_trade_id in "
			+ "(select id from trades where instrument_id in (select id from instruments where symbol = ?))", SYMBOL);
		jdbcTemplate.update("delete from holding_lots where buy_trade_id in "
			+ "(select id from trades where instrument_id in (select id from instruments where symbol = ?))", SYMBOL);
		jdbcTemplate.update(
			"delete from holdings where instrument_id in (select id from instruments where symbol = ?)", SYMBOL);
		jdbcTemplate.update(
			"delete from trades where instrument_id in (select id from instruments where symbol = ?)", SYMBOL);
		jdbcTemplate.update(
			"delete from orders where instrument_id in (select id from instruments where symbol = ?)", SYMBOL);
		jdbcTemplate.update(
			"delete from stock_candles where instrument_id in (select id from instruments where symbol = ?)", SYMBOL);
		jdbcTemplate.update("delete from stock_replay_sessions where service_date = ?", SERVICE_DATE);
		jdbcTemplate.update("delete from accounts where user_id in (select id from users where email = ?)", EMAIL);
		jdbcTemplate.update("delete from users where email = ?", EMAIL);
		jdbcTemplate.update("delete from instruments where symbol = ?", SYMBOL);
	}

	@Test
	@DisplayName("테스트 트랜잭션 없이도 최초 조회 → 저장 → 재조회가 통과한다 — 읽기 경계가 자기 트랜잭션 안에서 닫힌다")
	void readsAssemblesAndStoresAcrossSeparateTransactions() {
		PostSellFeedbackResponse first = postSellFeedbackService.getPostSellFeedback(userId, sellTradeId);

		assertThat(first.symbol()).isEqualTo(SYMBOL);
		assertThat(first.name()).isEqualTo("테스트종목208B");
		assertThat(first.instrumentId()).isNotNull();
		assertThat(first.buyAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, BUY_TIME));
		assertThat(first.sellAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME));
		assertThat(first.holdingMinutes()).isEqualTo(310);
		assertThat(first.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(first.sameSessionCompleted()).isTrue();
		assertThat(first.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(first.narrative()).isNotBlank();
		assertThat(feedbackRowCount()).isEqualTo(1L);

		PostSellFeedbackResponse second = postSellFeedbackService.getPostSellFeedback(userId, sellTradeId);

		assertThat(second.narrative()).isEqualTo(first.narrative());
		assertThat(second.narrativeSource()).isEqualTo(first.narrativeSource());
		assertThat(feedbackRowCount()).isEqualTo(1L);
	}

	private long feedbackRowCount() {
		return jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM trade_feedbacks WHERE trade_id = ?", Long.class, sellTradeId);
	}

	private void saveCandle(Instrument stock, LocalTime candleTime, String close) {
		BigDecimal price = new BigDecimal(close);
		stockCandleRepository.saveAndFlush(StockCandle.create(
			stock, ORIGIN_TRADE_DATE, candleTime, price, price.add(new BigDecimal("300")),
			price.subtract(new BigDecimal("300")), price, 1_000L, "TEST", NOW));
	}

	private Trade saveTrade(
		Account account,
		Instrument stock,
		StockReplaySession session,
		OrderSide side,
		BigDecimal price,
		Long realizedPnl,
		LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			account.getUser(), account, stock, side, OrderType.MARKET, new BigDecimal("10"),
			"idem-" + System.nanoTime(), "a".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, stock, session, side, price, new BigDecimal("10"),
			price.multiply(new BigDecimal("10")).longValueExact(), 102L, realizedPnl, executedAt, executedAt));
	}
}
