package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	PostSellFeedbackPeerComparisonGateIntegrationTest.GateTestConfig.class})
class PostSellFeedbackPeerComparisonGateIntegrationTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate TRADE_SERVICE_DATE = LocalDate.of(2032, 8, 4);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);

	private static final LocalDateTime VIEW_AT = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(16, 0));

	private static final String NARRATIVE_1 = "09시 30분에 70,000원에 매수한 뒤 14시 40분에 68,500원에 매도했습니다.";
	private static final String NARRATIVE_2 = "장 마감 뒤 종가는 69,200원이었습니다.";

	@Autowired
	private PostSellFeedbackService postSellFeedbackService;

	@Autowired
	private FakeNarrativeGenerator fakeNarrativeGenerator;

	@Autowired
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

	private User owner;
	private Account account;
	private Instrument stock;
	private Holding holding;
	private StockReplaySession tradeSession;
	private Trade sellTrade;

	@BeforeEach
	void setUp() {
		fakeNarrativeGenerator.reset();

		owner = userRepository
			.saveAndFlush(User.create("post-sell-peer-gate@finplay.com", "hash", "peergate212", VIEW_AT));
		account = accountRepository.saveAndFlush(
			Account.create(owner, Market.STOCK, VIEW_AT));
		stock = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST212G", "테스트종목212G", BigDecimal.valueOf(100), 10_000L, true, VIEW_AT));
		holding = holdingRepository.saveAndFlush(Holding.create(account, stock, VIEW_AT));
		tradeSession = saveSession(TRADE_SERVICE_DATE, ORIGIN_TRADE_DATE);

		saveCandle(BUY_TIME, "69500");
		saveCandle(LocalTime.of(11, 5), "70800");
		saveCandle(SELL_TIME, "68500");
		saveCandle(LocalTime.of(15, 5), "69500");
		saveCandle(LAST_CANDLE_TIME, "69200");

		sellTrade = saveSellTrade(tradeSession);
		allocate(sellTrade, saveLot(tradeSession, BUY_TIME));
	}

	@Test
	@DisplayName("보유 구간에 카드가 있어도 확정 집계 행이 없으면 peerComparison=NOT_YET이라 게이트가 닫힌 채고 재생성이 일어나지 않는다")
	void keepsTheGateClosedWhenTheCardExistsButNoConfirmedStatRowYet() {
		saveCard(LocalTime.of(9, 45), LocalTime.of(9, 50));
		fakeNarrativeGenerator.enqueue(NARRATIVE_1).enqueue(NARRATIVE_2);

		PostSellFeedbackResponse first = getPostSellFeedback();
		assertThat(first.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(first.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);

		PostSellFeedbackResponse second = getPostSellFeedback();

		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(1);
		assertThat(second.narrative()).isEqualTo(first.narrative());
		assertThat(tradeFeedbackRepository.findByTradeId(sellTrade.getId()))
			.get()
			.satisfies(feedback -> assertThat(feedback.isNarrativeFinalized()).isFalse());
	}

	@Test
	@DisplayName("보유 구간에 카드가 0건이면 peerComparison=NO_EVENT이고 게이트가 코드 수정 없이 열려 서술이 1회 재생성된다")
	void opensTheGateWhenNoCardExistsInTheHeldWindowBecauseNoEventIsNotNotYet() {
		fakeNarrativeGenerator.enqueue(NARRATIVE_1).enqueue(NARRATIVE_2);

		PostSellFeedbackResponse first = getPostSellFeedback();
		assertThat(first.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(first.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NO_EVENT);
		assertThat(first.narrative()).isEqualTo(NARRATIVE_1);

		PostSellFeedbackResponse second = getPostSellFeedback();

		assertThat(fakeNarrativeGenerator.callCount()).isEqualTo(2);
		assertThat(second.narrative()).isEqualTo(NARRATIVE_2);
		assertThat(second.narrative()).isNotEqualTo(first.narrative());
		assertThat(tradeFeedbackRepository.findByTradeId(sellTrade.getId()))
			.get()
			.satisfies(feedback -> {
				assertThat(feedback.getNarrative()).isEqualTo(NARRATIVE_2);
				assertThat(feedback.isNarrativeFinalized()).isTrue();
			});
	}

	private PostSellFeedbackResponse getPostSellFeedback() {
		return postSellFeedbackService.getPostSellFeedback(owner.getId(), sellTrade.getId());
	}

	private void saveCard(LocalTime windowStart, LocalTime windowEnd) {
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock,
			PriceMoveEventType.INTRADAY,
			ORIGIN_TRADE_DATE,
			windowStart,
			windowEnd,
			new BigDecimal("-0.018200"),
			new BigDecimal("3.2500"),
			windowStart + "부터 하락했습니다.",
			NarrativeSource.LLM,
			windowEnd.plusMinutes(1),
			VIEW_AT));
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
	static class GateTestConfig extends FeedbackFixedClockTestConfig {

		@Override
		protected LocalDateTime viewAt() {
			return VIEW_AT;
		}
	}
}
