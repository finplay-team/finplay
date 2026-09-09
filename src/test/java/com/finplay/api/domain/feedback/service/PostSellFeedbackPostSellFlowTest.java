package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.feedback.dto.response.Counterfactuals;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.dto.response.PostSellFlow;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.service.StockCandleDto;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PostSellFeedbackPostSellFlowTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long SELL_TRADE_ID = 2L;
	private static final Long INSTRUMENT_ID = 7L;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate TRADE_SERVICE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);

	private static final LocalDateTime JUST_BEFORE_GATE = LocalDateTime.of(TRADE_SERVICE_DATE,
		LocalTime.of(15, 29, 59));
	private static final LocalDateTime EXACTLY_AT_GATE = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(15, 30));
	private static final LocalDateTime NEXT_DAY_MORNING = LocalDateTime.of(TRADE_SERVICE_DATE.plusDays(1),
		LocalTime.of(10, 0));

	private static final BigDecimal SELL_PRICE = new BigDecimal("68500");

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository = mock(
		PriceMoveEventSourceRepository.class);

	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

	private Trade trade;

	private SellAllocationSummaryDto allocation;


	@Test
	@DisplayName("게이트 직전(15:29:59)에는 매도 후 흐름·반사실이 NOT_YET이고 가격 필드가 전부 빈다")
	void leavesPostSellFlowAndCounterfactualsNotYetJustBeforeMarketClose() {
		givenSameSessionSell();
		givenCandles(contractExampleCandles());
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(JUST_BEFORE_GATE);

		PostSellFlow flow = response.postSellFlow();
		assertThat(flow).isNotNull();
		assertThat(flow.status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(flow.closePrice()).isNull();
		assertThat(flow.closeAt()).isNull();
		assertThat(flow.sellToCloseRate()).isNull();
		assertThat(flow.postSellHighPrice()).isNull();
		assertThat(flow.postSellHighAt()).isNull();

		Counterfactuals counterfactuals = response.counterfactuals();
		assertThat(counterfactuals).isNotNull();
		assertThat(counterfactuals.status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(counterfactuals.atClose()).isNull();
		assertThat(counterfactuals.atHoldHigh()).isNull();
		assertThat(counterfactuals.atFirstMoveAfterBuy()).isNull();

		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdLowPrice()).isEqualByComparingTo("68100");
	}

	@Test
	@DisplayName("게이트 정각(15:30:00)에 매도 후 흐름·반사실이 READY로 열린다")
	void opensExactlyAtMarketCloseTime() {
		givenSameSessionSell();
		givenCandles(contractExampleCandles());
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
	}

	@Test
	@DisplayName("게이트 전에는 매도 이후 분봉 값이 응답 어디에도 나타나지 않는다")
	void neverExposesAnyPostSellCandleValueBeforeTheGate() {
		givenSameSessionSell();
		givenCandles(List.of(
			candle(BUY_TIME, "69500"),
			candle(LocalTime.of(11, 5), "70800"),
			candle(SELL_TIME, "68500"),
			candle(LocalTime.of(15, 5), "99000"),
			candle(LAST_CANDLE_TIME, "98000")));
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(JUST_BEFORE_GATE);

		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)));
		assertThat(response.postSellFlow().closePrice()).isNull();
		assertThat(response.postSellFlow().postSellHighPrice()).isNull();
		assertThat(response.counterfactuals().atClose()).isNull();
		PostSellFeedbackResponse afterGate = getPostSellFeedbackAt(EXACTLY_AT_GATE);
		assertThat(afterGate.postSellFlow().postSellHighPrice()).isEqualByComparingTo("99000");
	}


	@Test
	@DisplayName("전날 매도 건을 다음 날 오전 10:00에 조회해도 READY를 유지한다")
	void keepsReadyWhenAYesterdayTradeIsViewedDuringTheNextTradingSession() {
		givenSameSessionSell();
		givenCandles(contractExampleCandles());
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(NEXT_DAY_MORNING);

		assertThat(NEXT_DAY_MORNING.toLocalTime()).isBefore(LocalTime.of(15, 30));
		assertThat(NEXT_DAY_MORNING.toLocalDate()).isAfter(TRADE_SERVICE_DATE);

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("69200");
	}


	@Test
	@DisplayName("closePrice·closeAt과 atClose가 그 거래일 마지막 분봉(15:27)의 close다 — 리터럴 15:30이 아니다")
	void usesTheLastCandleOfTheTradingDayForCloseAndAtCloseScenario() {
		givenSameSessionSell();
		List<StockCandleDto> candles = contractExampleCandles();
		givenCandles(candles);
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);

		assertThat(candles).extracting(StockCandleDto::candleTime).doesNotContain(LocalTime.of(15, 30));

		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("69200");
		assertThat(response.postSellFlow().closeAt())
			.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LAST_CANDLE_TIME));
		assertThat(response.counterfactuals().atClose().price()).isEqualByComparingTo("69200");
		assertThat(response.counterfactuals().atClose().at())
			.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LAST_CANDLE_TIME));
	}


	@Test
	@DisplayName("게이트가 열렸고 분봉이 0건이면 status는 READY이고 값만 null이다")
	void reportsReadyWithNullValuesWhenTheGateIsOpenButThereIsNoCandle() {
		givenSameSessionSell();
		givenCandles(List.of());
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().closePrice()).isNull();
		assertThat(response.postSellFlow().closeAt()).isNull();
		assertThat(response.postSellFlow().sellToCloseRate()).isNull();
		assertThat(response.postSellFlow().postSellHighPrice()).isNull();
		assertThat(response.postSellFlow().postSellHighAt()).isNull();
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().atClose()).isNull();
		assertThat(response.counterfactuals().atHoldHigh()).isNull();
		assertThat(response.counterfactuals().atFirstMoveAfterBuy()).isNull();
	}


	@Test
	@DisplayName("sellToCloseRate는 (종가 − 매도가) ÷ 매도가다 — 종가가 높으면 양수이고 sellVsHighRate와 부호가 갈린다")
	void computesSellToCloseRateAgainstTheSellPriceNotTheClosePrice() {
		givenSameSessionSell();
		givenCandles(contractExampleCandles());
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);

		assertThat(response.postSellFlow().sellToCloseRate()).isEqualTo(new BigDecimal("0.0102"));
		assertThat(response.postSellFlow().sellToCloseRate()).isNotEqualTo(new BigDecimal("-0.0101"));
		assertThat(response.sellVsHighRate()).isEqualTo(new BigDecimal("-0.0325"));
		assertThat(response.postSellFlow().sellToCloseRate().signum())
			.isNotEqualTo(response.sellVsHighRate().signum());
	}


	@Test
	@DisplayName("매도 분봉이 그날 최고 종가여도 postSellHigh에 잡히지 않는다 — 경계가 배타다")
	void excludesTheSellMinuteCandleFromThePostSellHigh() {
		givenSameSessionSell();
		givenCandles(List.of(
			candle(BUY_TIME, "68000"),
			candle(SELL_TIME, "70000"),
			candle(LocalTime.of(15, 5), "69500"),
			candle(LAST_CANDLE_TIME, "69200")));
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);

		assertThat(response.holdHighPrice()).isEqualByComparingTo("70000");
		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME));
		assertThat(response.postSellFlow().postSellHighPrice()).isEqualByComparingTo("69500");
		assertThat(response.postSellFlow().postSellHighPrice()).isNotEqualByComparingTo("70000");
		assertThat(response.postSellFlow().postSellHighAt())
			.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 5)))
			.isNotEqualTo(response.holdHighAt());
	}

	@Test
	@DisplayName("마지막 분봉에 매도했으면 postSellHigh 두 값이 null이고 closePrice는 채워진다")
	void leavesPostSellHighNullWhenTheSellHappenedOnTheLastCandle() {
		givenSameSessionSell();
		givenCandles(List.of(
			candle(BUY_TIME, "69500"),
			candle(SELL_TIME, "68500")));
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("68500");
		assertThat(response.postSellFlow().closeAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME));
		assertThat(response.postSellFlow().postSellHighPrice()).isNull();
		assertThat(response.postSellFlow().postSellHighAt()).isNull();
	}


	@Test
	@DisplayName("atHoldHigh는 보유 구간 최고가와 같은 값·같은 시각이고 atFirstMoveAfterBuy는 첫 카드 windowEnd 분봉이다")
	void fillsThreeCounterfactualScenariosFromTheHoldPeriodAndTheDayClose() {
		givenSameSessionSell();
		givenCandles(contractExampleCandles());
		PriceMoveEvent card = givenCards(card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25)));
		givenSources(card, news("생산 차질", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15))));

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);
		Counterfactuals counterfactuals = response.counterfactuals();

		assertThat(counterfactuals.atHoldHigh().price()).isEqualByComparingTo("70800");
		assertThat(counterfactuals.atHoldHigh().at()).isEqualTo(response.holdHighAt());
		assertThat(counterfactuals.atFirstMoveAfterBuy().price()).isEqualByComparingTo("69300");
		assertThat(counterfactuals.atFirstMoveAfterBuy().at())
			.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 25)));
	}

	@Test
	@DisplayName("보유 구간 카드가 0건이면 atFirstMoveAfterBuy가 null이고 나머지 두 시나리오는 채워진다")
	void leavesAtFirstMoveAfterBuyNullWhenTheHoldPeriodHasNoCard() {
		givenSameSessionSell();
		givenCandles(contractExampleCandles());
		givenNoCards();

		Counterfactuals counterfactuals = getPostSellFeedbackAt(EXACTLY_AT_GATE).counterfactuals();

		assertThat(counterfactuals.atFirstMoveAfterBuy()).isNull();
		assertThat(counterfactuals.atClose()).isNotNull();
		assertThat(counterfactuals.atHoldHigh()).isNotNull();
	}

	@Test
	@DisplayName("첫 카드의 windowEnd 분봉이 없으면 atFirstMoveAfterBuy가 null이다")
	void leavesAtFirstMoveAfterBuyNullWhenThatWindowEndHasNoCandle() {
		givenSameSessionSell();
		givenCandles(contractExampleCandles());
		PriceMoveEvent card = givenCards(card(12L, LocalTime.of(11, 21), LocalTime.of(11, 26)));
		givenSources(card, news("생산 차질", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15))));

		Counterfactuals counterfactuals = getPostSellFeedbackAt(EXACTLY_AT_GATE).counterfactuals();

		assertThat(counterfactuals.atFirstMoveAfterBuy()).isNull();
	}


	@Test
	@DisplayName("반사실 3종의 returnRate가 수수료를 다시 계산해 api-contracts.md 예시 값 그대로 나온다 — peerComparison은 여전히 NOT_YET이다")
	void computesCounterfactualReturnRatesMatchingTheContractExampleWhilePeerComparisonStaysForTheNextItem() {
		givenSameSessionSell();
		givenCandles(contractExampleCandles());
		PriceMoveEvent card = givenCards(card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25)));
		givenSources(card, news("생산 차질", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15))));

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);

		assertThat(response.counterfactuals().atClose().returnRate()).isEqualTo(new BigDecimal("-0.0117"));
		assertThat(response.counterfactuals().atHoldHigh().returnRate()).isEqualTo(new BigDecimal("0.0111"));
		assertThat(response.counterfactuals().atFirstMoveAfterBuy().returnRate()).isEqualTo(new BigDecimal("-0.0103"));
		assertThat(response.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(response.peerComparison().priceMoveId()).isNull();
		assertThat(response.peerComparison().holderCount()).isNull();
		assertThat(response.peerComparison().soldWithin30MinRate()).isNull();
		assertThat(response.peerComparison().medianMinutesToSell()).isNull();
		assertThat(response.peerComparison().yourMinutesToSell()).isNull();
		assertThat(response.priceMoves()).isNotEmpty();
	}


	@Test
	@DisplayName("sameSessionCompleted=false면 매도 후 흐름·반사실·집단 비교가 필드 자체로 null이다")
	void leavesThePostSellBlocksThemselvesNullWhenTheTradeSpansMultipleOriginTradeDates() {
		trade = sellTrade();
		allocation = allocation(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE.plusDays(1));

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);

		assertThat(response.sameSessionCompleted()).isFalse();
		assertThat(response.postSellFlow()).isNull();
		assertThat(response.counterfactuals()).isNull();
		assertThat(response.peerComparison()).isNull();
	}


	private PostSellFeedbackResponse getPostSellFeedbackAt(LocalDateTime now) {
		StockPostSellFeedbackReader reader = new StockPostSellFeedbackReader(
			stockReplayService, priceMoveEventRepository,
			new PriceMoveSourceLoader(priceMoveEventSourceRepository), priceMovePeerStatRepository,
			Clock.fixed(now.atZone(KST).toInstant(), KST));
		return reader.read(trade, allocation);
	}

	private static List<StockCandleDto> contractExampleCandles() {
		return List.of(
			candle(BUY_TIME, "69500"),
			candle(LocalTime.of(11, 5), "70800"),
			candle(LocalTime.of(11, 25), "69300"),
			candle(LocalTime.of(14, 20), "68100"),
			candle(SELL_TIME, "68500"),
			candle(LocalTime.of(15, 5), "69500"),
			candle(LAST_CANDLE_TIME, "69200"));
	}

	private void givenSameSessionSell() {
		trade = sellTrade();
		allocation = allocation(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE);
	}

	private void givenCandles(List<StockCandleDto> candles) {
		when(stockReplayService.getFullDayCandles(INSTRUMENT_ID, ORIGIN_TRADE_DATE)).thenReturn(candles);
	}

	private void givenNoCards() {
		givenCards();
	}

	private PriceMoveEvent givenCards(PriceMoveEvent... cards) {
		when(priceMoveEventRepository
			.findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				any(), any(), any(), any(), any()))
			.thenReturn(List.of(cards));
		return cards.length == 0 ? null : cards[0];
	}

	private void givenSources(PriceMoveEvent card, MarketNewsItem newsItem) {
		when(priceMoveEventSourceRepository.findAllByPriceMoveEventIdIn(any()))
			.thenReturn(List.of(PriceMoveEventSource.of(card, newsItem)));
	}

	private static StockCandleDto candle(LocalTime candleTime, String close) {
		BigDecimal price = new BigDecimal(close);
		return new StockCandleDto(
			ORIGIN_TRADE_DATE, candleTime, price, price.add(new BigDecimal("500")),
			price.subtract(new BigDecimal("500")), price, 1_000L);
	}

	private static PriceMoveEvent card(Long id, LocalTime windowStart, LocalTime windowEnd) {
		PriceMoveEvent event = PriceMoveEvent.createStock(
			stockInstrument(),
			PriceMoveEventType.INTRADAY,
			ORIGIN_TRADE_DATE,
			windowStart,
			windowEnd,
			new BigDecimal("-0.018200"),
			new BigDecimal("3.2500"),
			windowStart + "부터 하락했습니다.",
			NarrativeSource.LLM,
			windowEnd.plusMinutes(1),
			LocalDateTime.of(ORIGIN_TRADE_DATE, windowEnd));
		ReflectionTestUtils.setField(event, "id", id);
		return event;
	}

	private static MarketNewsItem news(String title, LocalDateTime publishedAt) {
		return MarketNewsItem.create(
			stockInstrument(), MarketNewsItemType.NEWS, title, "hankyung.com",
			"https://news.example.test/" + title, publishedAt, publishedAt);
	}

	private static SellAllocationSummaryDto allocation(
		LocalDate earliestLotOriginTradeDate, LocalDate laterLotOriginTradeDate) {
		return new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"),
			LocalDateTime.of(TRADE_SERVICE_DATE, BUY_TIME),
			earliestLotOriginTradeDate,
			700_000L,
			105L,
			new BigDecimal("10"),
			List.of(earliestLotOriginTradeDate, laterLotOriginTradeDate));
	}

	private static Trade sellTrade() {
		Instrument instrument = stockInstrument();
		LocalDateTime resolvedAt = LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(8, 40));
		StockReplaySession session = StockReplaySession.ready(
			TRADE_SERVICE_DATE, ORIGIN_TRADE_DATE, resolvedAt, resolvedAt);
		LocalDateTime executedAt = LocalDateTime.of(TRADE_SERVICE_DATE, SELL_TIME);
		User user = User.create("trader@finplay.com", "password-hash", "trader", executedAt);
		Account account = Account.create(user, Market.STOCK, executedAt);
		Order order = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET, new BigDecimal("10"), "idem-key",
			"h".repeat(64), executedAt);
		Trade trade = Trade.of(
			order, account, instrument, session, OrderSide.SELL, SELL_PRICE, new BigDecimal("10"), 685_000L, 102L,
			-15_207L, executedAt, executedAt);
		ReflectionTestUtils.setField(trade, "id", SELL_TRADE_ID);
		return trade;
	}

	private static Instrument stockInstrument() {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true,
			LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME));
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		return instrument;
	}
}
