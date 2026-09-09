package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
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
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class PostSellFeedbackGateIntegrationTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate TRADE_SERVICE_DATE = LocalDate.of(2031, 8, 4);
	private static final LocalDate NEXT_DAY = LocalDate.of(2031, 8, 5);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(11, 30);
	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);

	private static final LocalDateTime SAME_DAY_VIEW = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(11, 40));
	private static final LocalDateTime NEXT_DAY_VIEW = LocalDateTime.of(NEXT_DAY, LocalTime.of(10, 0));

	private static final BigDecimal SELL_PRICE = new BigDecimal("68500");

	private static final LocalDateTime NOW = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(8, 0));

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
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private TestClock clock;

	private TestClock mutableClock;

	private User owner;
	private Account account;
	private Instrument stock;
	private Trade sellTrade;

	@BeforeEach
	void setUp() {
		mutableClock = clock;
		mutableClock.set(SAME_DAY_VIEW);

		owner = userRepository.saveAndFlush(User.create("post-sell-gate@finplay.com", "hash", "gate208", NOW));
		account = accountRepository.saveAndFlush(
			Account.create(owner, Market.STOCK, NOW));
		stock = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST208G", "테스트종목208G", BigDecimal.valueOf(100), 10_000L, true, NOW));

		LocalDateTime resolvedAt = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(8, 40));
		StockReplaySession session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(TRADE_SERVICE_DATE, ORIGIN_TRADE_DATE, resolvedAt, resolvedAt));
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, stock, NOW));

		Trade buyTrade = saveTrade(
			session, OrderSide.BUY, new BigDecimal("10"), new BigDecimal("70000"), null,
			LocalDateTime.of(TRADE_SERVICE_DATE, BUY_TIME));
		HoldingLot lot = holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, new BigDecimal("10"), new BigDecimal("70000"), 105L,
			LocalDateTime.of(TRADE_SERVICE_DATE, BUY_TIME), NOW));
		sellTrade = saveTrade(
			session, OrderSide.SELL, new BigDecimal("10"), SELL_PRICE, -15_207L,
			LocalDateTime.of(TRADE_SERVICE_DATE, SELL_TIME));
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sellTrade, lot, new BigDecimal("10"), 700_000L, 105L, NOW));
	}

	@Test
	@DisplayName("같은 서비스 날짜 11:40 조회에서는 revealTime 11:55 카드가 감춰진다")
	void hidesACardWhoseRevealTimeHasNotPassedOnTheServiceDateOfTheTrade() {
		saveRevealedCard();
		saveHiddenCard();
		mutableClock.set(SAME_DAY_VIEW);

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.priceMoves())
			.extracting(move -> move.windowEnd().toLocalTime())
			.containsExactly(LocalTime.of(10, 25));
	}

	@Test
	@DisplayName("다음 날 오전 10:00에 조회해도 그 서비스 날짜의 오후 카드가 계속 보인다")
	void keepsCardsFromAPastServiceDateOpenWhenViewedOnALaterMorning() {
		saveRevealedCard();
		saveHiddenCard();
		mutableClock.set(NEXT_DAY_VIEW);

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(NEXT_DAY_VIEW.toLocalTime()).isBefore(LocalTime.of(11, 55));
		assertThat(response.priceMoves())
			.extracting(move -> move.windowEnd().toLocalTime())
			.containsExactly(LocalTime.of(10, 25), LocalTime.of(11, 25));
	}

	@Test
	@DisplayName("감춰진 카드의 이른 기사는 buyToNewsMinutes에 섞이지 않고, 그 카드가 열리면 값이 바뀐다")
	void excludesHiddenCardSourcesFromBuyToNewsMinutesUntilThatCardOpens() {
		saveRevealedCard();
		saveHiddenCard();

		mutableClock.set(SAME_DAY_VIEW);
		PostSellFeedbackResponse beforeReveal = getPostSellFeedback();
		assertThat(beforeReveal.buyToNewsMinutes()).isEqualTo(45);
		assertThat(beforeReveal.buyToNewsMinutes()).isNotEqualTo(-30);
		assertThat(beforeReveal.priceMoves()).flatExtracting(HeldPriceMoveItem::sources)
			.extracting(NewsItem::title)
			.doesNotContain("장 초반 기사");

		mutableClock.set(LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(12, 0)));
		PostSellFeedbackResponse afterReveal = getPostSellFeedback();

		assertThat(afterReveal.buyToNewsMinutes()).isEqualTo(-30);
		assertThat(afterReveal.priceMoves()).flatExtracting(HeldPriceMoveItem::sources)
			.extracting(NewsItem::title)
			.contains("장 초반 기사");
	}

	@Test
	@DisplayName("카드 간격은 windowEnd 기준이고 windowStart 동률은 id로 가르며 근거는 발행시각 내림차순이다")
	void computesIntervalsFromWindowEndAndKeepsContractOrdering() {
		PriceMoveEvent intraday = saveRevealedCard();
		PriceMoveEvent gap = saveCard(
			PriceMoveEventType.OPENING_GAP, LocalTime.of(10, 20), LocalTime.of(10, 20), LocalTime.of(9, 0));
		saveSource(gap, saveNews("갭 근거 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(8, 40))));
		saveSource(intraday, saveNews("늦은 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 24))));
		mutableClock.set(SAME_DAY_VIEW);

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(intraday.getWindowStart()).isEqualTo(gap.getWindowStart());
		assertThat(intraday.getId()).isLessThan(gap.getId());

		assertThat(response.priceMoves())
			.extracting(HeldPriceMoveItem::id, HeldPriceMoveItem::minutesAfterBuy,
				HeldPriceMoveItem::minutesBeforeSell)
			.containsExactly(
				tuple(intraday.getId(), 55, 65),
				tuple(gap.getId(), 50, 70));
		assertThat(response.priceMoves().get(0).sources())
			.extracting(NewsItem::title)
			.containsExactly("늦은 기사", "10:15 기사");
	}

	@Test
	@DisplayName("실제 분봉에서 극값을 close로 고르고 보유 구간 밖 분봉은 쓰지 않는다")
	void picksHoldExtremesFromRealCandleClosesWithinTheHoldPeriod() {
		saveCandle(LocalTime.of(9, 20), "71000", "71200", "70900");
		saveCandle(BUY_TIME, "69500", "72000", "69400");
		saveCandle(LocalTime.of(10, 30), "70800", "71500", "70700");
		saveCandle(LocalTime.of(11, 20), "68100", "68200", "67500");
		saveCandle(SELL_TIME, "68500", "68600", "68000");
		saveCandle(LocalTime.of(11, 40), "67000", "67100", "66900");
		mutableClock.set(SAME_DAY_VIEW);

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 30)));
		assertThat(response.holdLowPrice()).isEqualByComparingTo("68100");
		assertThat(response.holdLowAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 20)));
		assertThat(response.holdHighPrice())
			.isNotEqualByComparingTo("72000")
			.isNotEqualByComparingTo("71000");
		assertThat(response.holdLowPrice())
			.isNotEqualByComparingTo("67500")
			.isNotEqualByComparingTo("67000");
		assertThat(response.sellVsHighRate()).isEqualTo(new BigDecimal("-0.0325"));
		assertThat(response.sellVsLowRate()).isEqualTo(new BigDecimal("0.0059"));
		assertThat(response.sameSessionCompleted()).isTrue();
		assertThat(response.holdingMinutes()).isEqualTo(120);
	}

	@Test
	@DisplayName("보유 구간에 분봉이 없으면 극값 여섯 값만 null이고 조회는 그대로 성립한다")
	void leavesExtremesNullWhenNoCandleFallsInsideTheHoldPeriod() {
		saveCandle(LocalTime.of(9, 20), "71000", "71200", "70900");
		saveCandle(LocalTime.of(11, 40), "67000", "67100", "66900");
		mutableClock.set(SAME_DAY_VIEW);

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.holdHighPrice()).isNull();
		assertThat(response.holdHighAt()).isNull();
		assertThat(response.holdLowPrice()).isNull();
		assertThat(response.holdLowAt()).isNull();
		assertThat(response.sellVsHighRate()).isNull();
		assertThat(response.sellVsLowRate()).isNull();
		assertThat(response.sellPrice()).isEqualByComparingTo(SELL_PRICE);
		assertThat(response.holdingMinutes()).isEqualTo(120);
	}

	@Test
	@DisplayName("게이트 직전 15:29:59에는 매도 후 흐름·반사실이 NOT_YET이고 매도 이후 분봉 값이 새지 않는다")
	void leavesPostSellBlocksNotYetJustBeforeMarketClose() {
		saveFullDayCandles();
		mutableClock.set(LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(15, 29, 59)));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(response.postSellFlow().closePrice()).isNull();
		assertThat(response.postSellFlow().closeAt()).isNull();
		assertThat(response.postSellFlow().sellToCloseRate()).isNull();
		assertThat(response.postSellFlow().postSellHighPrice()).isNull();
		assertThat(response.postSellFlow().postSellHighAt()).isNull();
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(response.counterfactuals().atClose()).isNull();
		assertThat(response.counterfactuals().atHoldHigh()).isNull();
		assertThat(response.counterfactuals().atFirstMoveAfterBuy()).isNull();
		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
	}

	@Test
	@DisplayName("게이트 정각 15:30에 열리고 closePrice·atClose가 마지막 분봉 15:27이다")
	void opensAtMarketCloseAndUsesTheLastCandleOfTheDay() {
		saveFullDayCandles();
		mutableClock.set(LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(15, 30)));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("69200");
		assertThat(response.postSellFlow().closeAt())
			.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LAST_CANDLE_TIME));
		assertThat(response.postSellFlow().sellToCloseRate()).isEqualTo(new BigDecimal("0.0102"));
		assertThat(response.postSellFlow().postSellHighPrice()).isEqualByComparingTo("69500");
		assertThat(response.postSellFlow().postSellHighAt())
			.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 5)));
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().atClose().at())
			.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LAST_CANDLE_TIME));
		assertThat(response.counterfactuals().atClose().returnRate()).isEqualTo(new BigDecimal("-0.0117"));
		assertThat(response.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NO_EVENT);
		assertThat(response.peerComparison().priceMoveId()).isNull();
	}

	@Test
	@DisplayName("전날 매도 건을 다음 날 오전 10:00에 조회해도 READY를 유지한다")
	void keepsPostSellFlowReadyForAYesterdayTradeViewedTheNextMorning() {
		saveFullDayCandles();
		mutableClock.set(NEXT_DAY_VIEW);

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(NEXT_DAY_VIEW.toLocalTime()).isBefore(LocalTime.of(15, 30));
		assertThat(NEXT_DAY_VIEW.toLocalDate()).isAfter(TRADE_SERVICE_DATE);

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("69200");
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
	}

	@Test
	@DisplayName("게이트가 열렸고 분봉이 0건이면 status는 READY이고 값만 null이다")
	void reportsReadyWithNullValuesWhenTheGateIsOpenWithoutCandles() {
		mutableClock.set(LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(15, 30)));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().closePrice()).isNull();
		assertThat(response.postSellFlow().postSellHighPrice()).isNull();
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().atClose()).isNull();
		assertThat(response.counterfactuals().atHoldHigh()).isNull();
	}

	private void saveFullDayCandles() {
		saveCandle(BUY_TIME, "69500", "70000", "69000");
		saveCandle(LocalTime.of(11, 5), "70800", "71500", "70700");
		saveCandle(SELL_TIME, "68500", "68600", "68000");
		saveCandle(LocalTime.of(15, 5), "69500", "99000", "69000");
		saveCandle(LAST_CANDLE_TIME, "69200", "69300", "69100");
	}

	private PostSellFeedbackResponse getPostSellFeedback() {
		return postSellFeedbackService.getPostSellFeedback(owner.getId(), sellTrade.getId());
	}

	private PriceMoveEvent saveRevealedCard() {
		PriceMoveEvent card = saveCard(
			PriceMoveEventType.INTRADAY, LocalTime.of(10, 20), LocalTime.of(10, 25), LocalTime.of(10, 26));
		saveSource(card, saveNews("10:15 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 15))));
		return card;
	}

	private PriceMoveEvent saveHiddenCard() {
		PriceMoveEvent card = saveCard(
			PriceMoveEventType.INTRADAY, LocalTime.of(11, 20), LocalTime.of(11, 25), LocalTime.of(11, 55));
		saveSource(card, saveNews("11:50 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 50))));
		saveSource(card, saveNews("장 초반 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 0))));
		return card;
	}

	private PriceMoveEvent saveCard(
		PriceMoveEventType eventType, LocalTime windowStart, LocalTime windowEnd, LocalTime revealTime) {
		return priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock,
			eventType,
			ORIGIN_TRADE_DATE,
			windowStart,
			windowEnd,
			new BigDecimal("-0.018200"),
			new BigDecimal("3.2500"),
			windowStart + "부터 하락했습니다.",
			NarrativeSource.LLM,
			revealTime,
			NOW));
	}

	private MarketNewsItem saveNews(String title, LocalDateTime publishedAt) {
		return marketNewsItemRepository.saveAndFlush(MarketNewsItem.create(
			stock,
			MarketNewsItemType.NEWS,
			title,
			"hankyung.com",
			"https://news.example.test/gate208/" + title,
			publishedAt,
			publishedAt));
	}

	private void saveSource(PriceMoveEvent card, MarketNewsItem newsItem) {
		priceMoveEventSourceRepository.saveAndFlush(PriceMoveEventSource.of(card, newsItem));
	}

	private void saveCandle(LocalTime candleTime, String close, String high, String low) {
		stockCandleRepository.saveAndFlush(StockCandle.create(
			stock,
			ORIGIN_TRADE_DATE,
			candleTime,
			new BigDecimal(close),
			new BigDecimal(high),
			new BigDecimal(low),
			new BigDecimal(close),
			1_000L,
			"TEST",
			NOW));
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

}
