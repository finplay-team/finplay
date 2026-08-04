// 테스트 트랜잭션 없이 실제 커밋으로 매도 회고 조회를 돌려 reader의 트랜잭션 경계(open-in-view=false)를 지키는 회귀 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockCandleRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

// **이 클래스에 @Transactional이 없는 것이 이 파일의 존재 이유다.** 다른 매도 회고 통합 테스트 넷은 공유
// 컨테이너를 더럽히지 않으려고 클래스 트랜잭션으로 감싸는데, 그러면 테스트 트랜잭션이 조회 내내 열려 있어
// **reader의 @Transactional(readOnly = true)이 없어져도 lazy 연관이 그대로 초기화돼 전부 초록이 된다.**
// 운영은 open-in-view=false라 그 경계가 사라지면 trade.getInstrument() 접근이 LazyInitializationException으로
// 터진다 — 이 이슈의 핵심 설계(읽기·LLM 호출·저장 3분할)를 지키는 회귀가 여기 하나다.
// 레포 선례는 CandleQueryServiceIntegrationTest(클래스 @Transactional 없음)다.
//
// 커밋한 행은 @AfterEach가 FK 역순으로 직접 지운다. 서비스 날짜는 이 파일 전용 연도(2031)이고 다른 클래스가
// 쓰는 08-03·08-04·08-05와도 겹치지 않는다 — stock_replay_sessions.UNIQUE(service_date) 충돌이 전체 실행에서만
// 터지는 함정을 한 번 겪었다.
//
// 실제 LLM을 부르지 않는다 — api-key가 `not-configured`라 생성기가 호출 전에 실패를 돌려주고 NarrativeService가
// §템플릿 문장으로 폴백한다 (ADR-0011·PRD C-005).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostSellFeedbackBoundaryIntegrationTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	// 이 파일 전용 서비스 날짜 — 다른 매도 회고 테스트(2031-08-03·04·05)와 겹치지 않는다.
	private static final LocalDate SERVICE_DATE = LocalDate.of(2031, 8, 6);

	private static final String SYMBOL = "TEST208B";
	private static final String EMAIL = "post-sell-boundary@finplay.com";

	// 체결 시각에 소수 초를 붙인다 — 운영의 DATETIME(6) 모양이며 경계·분 계산이 실 DB 값 위에서도 맞는지 본다.
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
			Account.create(owner, com.finplay.api.account.domain.Market.STOCK, NOW));
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

	// 커밋한 행을 FK 역순으로 지운다 — 이 클래스가 만든 것만 좁혀 지우고 남의 행은 건드리지 않는다.
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

	// reader의 트랜잭션이 사라지면(애노테이션 제거·서비스로 인라인) 이 조회가 trade.getInstrument() 접근에서
	// LazyInitializationException으로 터진다 — 테스트 트랜잭션이 없어서 붙잡아 줄 세션이 없다.
	@Test
	@DisplayName("테스트 트랜잭션 없이도 최초 조회 → 저장 → 재조회가 통과한다 — 읽기 경계가 자기 트랜잭션 안에서 닫힌다")
	void readsAssemblesAndStoresAcrossSeparateTransactions() {
		PostSellFeedbackResponse first = postSellFeedbackService.getPostSellFeedback(userId, sellTradeId);

		// lazy 연관을 값으로 바꿔 나온 필드들 — 경계가 무효화되면 여기 닿기 전에 예외가 난다.
		assertThat(first.symbol()).isEqualTo(SYMBOL);
		assertThat(first.name()).isEqualTo("테스트종목208B");
		assertThat(first.instrumentId()).isNotNull();
		// 체결 시각은 초를 그대로 싣고, 분 단위 값은 분 축에서 잰다.
		assertThat(first.buyAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, BUY_TIME));
		assertThat(first.sellAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME));
		assertThat(first.holdingMinutes()).isEqualTo(310);
		assertThat(first.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(first.sameSessionCompleted()).isTrue();
		assertThat(first.narrativeStatus()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(first.narrative()).isNotBlank();
		// 저장이 실제로 커밋됐다 — 테스트 트랜잭션이 없으므로 롤백이 아니라 커밋이다.
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
