package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PostSellFeedbackDerivedFactsTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long SELL_TRADE_ID = 2L;
	private static final Long INSTRUMENT_ID = 7L;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate TODAY = LocalDate.of(2026, 8, 5);
	private static final LocalDate PAST_SERVICE_DATE = LocalDate.of(2026, 8, 3);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SUB_SECOND_BUY_TIME = LocalTime.of(9, 30, 17, 400_000_000);
	private static final LocalTime SUB_SECOND_SELL_TIME = LocalTime.of(14, 40, 5);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	private static final LocalTime NOW_TIME = LocalTime.of(15, 0);

	private static final BigDecimal SELL_PRICE = new BigDecimal("68500");

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository = mock(
		PriceMoveEventSourceRepository.class);

	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

	private final Clock clock = Clock.fixed(TODAY.atTime(NOW_TIME).atZone(KST).toInstant(), KST);

	private final StockPostSellFeedbackReader stockPostSellFeedbackReader = new StockPostSellFeedbackReader(
		stockReplayService, priceMoveEventRepository,
		new PriceMoveSourceLoader(priceMoveEventSourceRepository), priceMovePeerStatRepository, clock);

	private Trade trade;

	private SellAllocationSummaryDto allocation;

	@Test
	@DisplayName("극값은 분봉 close로 고른다 — high·low가 더 극단인 봉이 있어도 그 값을 쓰지 않는다")
	void picksHoldExtremesFromCandleCloseNotHighOrLow() {
		givenSameSessionSell();
		List<StockCandleDto> candles = List.of(
			candle(LocalTime.of(9, 20), "71000", "71200", "70900"),
			candle(LocalTime.of(9, 30), "69500", "72000", "69400"),
			candle(LocalTime.of(11, 5), "70800", "71500", "70700"),
			candle(LocalTime.of(14, 20), "68100", "68200", "67500"),
			candle(LocalTime.of(14, 40), "68500", "68600", "68000"),
			candle(LocalTime.of(14, 50), "67000", "67100", "66900"));
		givenCandles(candles);

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(candles).anySatisfy(candle -> {
			assertThat(candle.high()).isGreaterThan(candle.close());
			assertThat(candle.low()).isLessThan(candle.close());
		});

		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)));
		assertThat(response.holdLowPrice()).isEqualByComparingTo("68100");
		assertThat(response.holdLowAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 20)));
		assertThat(response.holdHighPrice()).isNotEqualByComparingTo("72000");
		assertThat(response.holdLowPrice()).isNotEqualByComparingTo("67500");
		assertThat(response.holdHighPrice()).isNotEqualByComparingTo("71000");
		assertThat(response.holdLowPrice()).isNotEqualByComparingTo("67000");

		assertThat(response.sellVsHighRate()).isEqualTo(new BigDecimal("-0.0325"));
		assertThat(response.sellVsLowRate()).isEqualTo(new BigDecimal("0.0059"));
	}

	@Test
	@DisplayName("보유 구간은 양 끝을 포함한다 — 매수 분·매도 분의 봉도 극값 후보다")
	void includesTheCandlesAtBothBoundariesOfTheHoldPeriod() {
		givenSameSessionSell();
		givenCandles(List.of(
			candle(LocalTime.of(9, 29), "80000", "80000", "80000"),
			candle(BUY_TIME, "70800", "70800", "70800"),
			candle(LocalTime.of(11, 5), "69000", "69000", "69000"),
			candle(SELL_TIME, "68100", "68100", "68100"),
			candle(LocalTime.of(14, 41), "60000", "60000", "60000")));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, BUY_TIME));
		assertThat(response.holdLowAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME));
		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdLowPrice()).isEqualByComparingTo("68100");
	}

	@Test
	@DisplayName("극값이 동률이면 이른 분봉을 고른다")
	void breaksExtremeTiesByTheEarlierCandle() {
		givenSameSessionSell();
		givenCandles(List.of(
			candle(LocalTime.of(10, 0), "70800", "70800", "70800"),
			candle(LocalTime.of(11, 5), "68100", "68100", "68100"),
			candle(LocalTime.of(13, 0), "70800", "70800", "70800"),
			candle(LocalTime.of(14, 0), "68100", "68100", "68100")));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 0)));
		assertThat(response.holdLowAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)));
	}

	@Test
	@DisplayName("보유 구간에 분봉이 없으면 극값 여섯 값이 전부 null이고 조회는 200이다")
	void leavesAllSixExtremeFieldsNullWhenTheHoldPeriodHasNoCandle() {
		givenSameSessionSell();
		givenCandles(List.of(
			candle(LocalTime.of(9, 0), "70000", "70000", "70000"),
			candle(LocalTime.of(15, 0), "69000", "69000", "69000")));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.holdHighPrice()).isNull();
		assertThat(response.holdHighAt()).isNull();
		assertThat(response.holdLowPrice()).isNull();
		assertThat(response.holdLowAt()).isNull();
		assertThat(response.sellVsHighRate()).isNull();
		assertThat(response.sellVsLowRate()).isNull();
		assertThat(response.sellPrice()).isEqualByComparingTo(SELL_PRICE);
	}

	@Test
	@DisplayName("분봉을 원본 거래일로 읽는다 — 서비스 날짜가 아니다")
	void readsCandlesByOriginTradeDateNotServiceDate() {
		givenSameSessionSell();
		givenCandles(List.of(candle(LocalTime.of(11, 5), "70800", "70800", "70800")));

		getPostSellFeedback();

		verify(stockReplayService).getFullDayCandles(INSTRUMENT_ID, ORIGIN_TRADE_DATE);
	}

	@Test
	@DisplayName("카드 간격은 windowEnd 기준이다 — 11:20~11:25 카드가 매수 115분 뒤·매도 195분 전이다")
	void computesCardIntervalsFromWindowEnd() {
		givenSameSessionSell();
		givenCandles(List.of());
		PriceMoveEvent card = givenCards(card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25)));
		givenSources(card, news("생산 차질", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15))));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.priceMoves()).singleElement().satisfies(move -> {
			assertThat(move.id()).isEqualTo(12L);
			assertThat(move.windowStart()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 20)));
			assertThat(move.windowEnd()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 25)));
			assertThat(move.changeRate()).isEqualByComparingTo("-0.018200");
			assertThat(move.minutesAfterBuy()).isEqualTo(115);
			assertThat(move.minutesBeforeSell()).isEqualTo(195);
			assertThat(move.minutesAfterBuy()).isNotEqualTo(110);
			assertThat(move.minutesBeforeSell()).isNotEqualTo(200);
			assertThat(move.narrative()).isNotEmpty();
			assertThat(move.sources()).extracting(NewsItem::title).containsExactly("생산 차질");
		});
	}

	@Test
	@DisplayName("카드는 보유 구간의 두 시각을 windowEnd 범위로 넘겨 조회한다")
	void queriesCardsWithTheHoldPeriodAsTheWindowEndRange() {
		givenSameSessionSell();
		givenCandles(List.of());
		givenCards();

		getPostSellFeedback();

		verify(priceMoveEventRepository)
			.findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				eq(INSTRUMENT_ID), eq(ORIGIN_TRADE_DATE), eq(BUY_TIME), eq(SELL_TIME), any());
	}

	@Test
	@DisplayName("카드 목록의 순서와 각 카드의 근거 순서를 조회 결과 그대로 유지한다")
	void keepsCardAndSourceOrderFromTheQuery() {
		givenSameSessionSell();
		givenCandles(List.of());
		PriceMoveEvent earlier = card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25));
		PriceMoveEvent later = card(13L, LocalTime.of(13, 20), LocalTime.of(13, 25));
		givenCards(earlier, later);
		givenSources(
			source(earlier, news("늦은 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 24)))),
			source(earlier, news("이른 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)))),
			source(later, news("오후 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(13, 20)))));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.priceMoves())
			.extracting(HeldPriceMoveItem::id, move -> move.windowStart().toLocalTime())
			.containsExactly(tuple(12L, LocalTime.of(11, 20)), tuple(13L, LocalTime.of(13, 20)));
		assertThat(response.priceMoves().get(0).sources())
			.extracting(NewsItem::title)
			.containsExactly("늦은 기사", "이른 기사");
		assertThat(response.priceMoves().get(1).sources())
			.extracting(NewsItem::title)
			.containsExactly("오후 기사");
	}

	@Test
	@DisplayName("매수가 기사보다 앞서면 buyToNewsMinutes가 양수다 — 09:30 매수·11:15 기사면 105다")
	void buyToNewsMinutesIsPositiveWhenTheBuyCameBeforeTheArticle() {
		givenSameSessionSell();
		givenCandles(List.of());
		PriceMoveEvent card = givenCards(card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25)));
		givenSources(card, news("생산 차질", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15))));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.buyToNewsMinutes()).isEqualTo(105);
		assertThat(response.buyToNewsMinutes()).isNotEqualTo(-105);
	}

	@Test
	@DisplayName("기사가 매수보다 앞서면 buyToNewsMinutes가 음수다")
	void buyToNewsMinutesIsNegativeWhenTheArticleCameBeforeTheBuy() {
		givenSameSessionSell();
		givenCandles(List.of());
		PriceMoveEvent card = givenCards(card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25)));
		givenSources(card, news("장 초반 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 0))));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.buyToNewsMinutes()).isEqualTo(-30);
	}

	@Test
	@DisplayName("근거 기사가 여럿이면 가장 이른 발행시각으로 잰다")
	void buyToNewsMinutesUsesTheEarliestSourceAcrossAllCards() {
		givenSameSessionSell();
		givenCandles(List.of());
		PriceMoveEvent first = card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25));
		PriceMoveEvent second = card(13L, LocalTime.of(13, 20), LocalTime.of(13, 25));
		givenCards(first, second);
		givenSources(
			source(first, news("11:15 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)))),
			source(second, news("10:00 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 0)))));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.buyToNewsMinutes()).isEqualTo(30);
	}

	@Test
	@DisplayName("보유 구간 카드가 없으면 buyToNewsMinutes가 null이고 priceMoves는 []다")
	void buyToNewsMinutesIsNullWhenThereIsNoHeldCard() {
		givenSameSessionSell();
		givenCandles(List.of());
		givenCards();

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.buyToNewsMinutes()).isNull();
		assertThat(response.priceMoves()).isEmpty();
	}

	@Test
	@DisplayName("근거는 게이트를 통과한 카드 id로만 조회한다 — 감춰진 카드의 기사가 buyToNewsMinutes에 섞이지 않는다")
	void asksSourcesOnlyForRevealedCardIds() {
		givenSameSessionSell();
		givenCandles(List.of());
		PriceMoveEvent revealed = card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25));
		givenCards(revealed);
		givenSources(
			source(revealed, news("생산 차질", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)))));

		PostSellFeedbackResponse response = getPostSellFeedback();

		ArgumentCaptor<List<Long>> captor = ArgumentCaptor.captor();
		verify(priceMoveEventSourceRepository).findAllByPriceMoveEventIdIn(captor.capture());
		assertThat(captor.getValue()).containsExactly(12L).doesNotContain(99L);
		assertThat(response.buyToNewsMinutes()).isEqualTo(105);
	}

	@Test
	@DisplayName("초가 붙은 체결시각에서도 계약 예시의 분 단위 값 넷이 그대로 나온다")
	void reproducesContractMinuteValuesFromSubSecondExecutionTimes() {
		givenSubSecondSell();
		givenCandles(List.of(
			candle(LocalTime.of(9, 30), "69500"),
			candle(LocalTime.of(11, 5), "70800"),
			candle(SELL_TIME, "68500")));
		PriceMoveEvent card = givenCards(card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25)));
		givenSources(card, news("생산 차질", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15))));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.buyAt().getNano()).isNotZero();
		assertThat(response.sellAt().getSecond()).isNotZero();
		assertThat(response.buyAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SUB_SECOND_BUY_TIME));
		assertThat(response.sellAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SUB_SECOND_SELL_TIME));

		assertThat(response.holdingMinutes()).isEqualTo(310).isNotEqualTo(309);
		assertThat(response.priceMoves()).singleElement().satisfies(move -> {
			assertThat(move.minutesAfterBuy()).isEqualTo(115).isNotEqualTo(114);
			assertThat(move.minutesBeforeSell()).isEqualTo(195);
		});
		assertThat(response.buyToNewsMinutes()).isEqualTo(105).isNotEqualTo(104);
	}

	@Test
	@DisplayName("매수 분봉이 보유 구간 최고가면 초가 붙은 체결시각에도 그 봉이 잡힌다")
	void keepsTheBuyMinuteCandleAsHoldHighWithSubSecondExecutionTimes() {
		givenSubSecondSell();
		givenCandles(List.of(
			candle(LocalTime.of(9, 30), "70800"),
			candle(LocalTime.of(9, 31), "69000"),
			candle(LocalTime.of(11, 5), "69500"),
			candle(SELL_TIME, "68100")));
		givenCards();

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)));
		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdHighAt()).isNotEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)));
		assertThat(response.holdHighPrice()).isNotEqualByComparingTo("69500");
		assertThat(response.holdLowAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME));
		assertThat(response.sellVsHighRate()).isEqualTo(new BigDecimal("-0.0325"));
	}

	@Test
	@DisplayName("기사가 매수보다 이르면 초가 붙은 체결시각에도 분 수가 절삭 방향에 흔들리지 않는다")
	void keepsNegativeBuyToNewsMinutesExactWithSubSecondExecutionTimes() {
		givenSubSecondSell();
		givenCandles(List.of());
		PriceMoveEvent card = givenCards(card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25)));
		givenSources(card, news("장 전 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(8, 59, 50))));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.buyToNewsMinutes()).isEqualTo(-31);
		assertThat(response.buyToNewsMinutes()).isNotEqualTo(-30);
	}

	@Test
	@DisplayName("과거 서비스 날짜의 체결이면 게이트 상한이 그날 끝이다 — 오늘 벽시계가 아니다")
	void usesEndOfDayCutoffForATradeFromAPastServiceDate() {
		givenSameSessionSell(PAST_SERVICE_DATE);
		givenCandles(List.of());
		givenCards();

		getPostSellFeedback();

		assertThat(capturedRevealCutoff()).isAfterOrEqualTo(LocalTime.of(23, 59, 59));
		assertThat(capturedRevealCutoff()).isNotEqualTo(NOW_TIME);
	}

	@Test
	@DisplayName("서비스 날짜가 오늘이면 게이트 상한이 현재 시각이다")
	void usesTheCurrentWallClockCutoffForATradeFromToday() {
		givenSameSessionSell(TODAY);
		givenCandles(List.of());
		givenCards();

		getPostSellFeedback();

		assertThat(capturedRevealCutoff()).isEqualTo(NOW_TIME);
	}

	@Test
	@DisplayName("서비스 날짜가 미래면 카드를 조회하지 않고 []다")
	void returnsNoCardsWithoutQueryingForAFutureServiceDate() {
		givenSameSessionSell(TODAY.plusDays(1));
		givenCandles(List.of());

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.priceMoves()).isEmpty();
		assertThat(response.buyToNewsMinutes()).isNull();
		verifyNoInteractions(priceMoveEventRepository, priceMoveEventSourceRepository);
	}

	@Test
	@DisplayName("sameSessionCompleted=false면 파생 사실이 전부 null·priceMoves는 []이고 분봉·카드를 읽지 않는다")
	void skipsAllDerivedFactsWhenTheTradeSpansMultipleOriginTradeDates() {
		LocalDate otherOriginTradeDate = LocalDate.of(2026, 7, 30);
		trade = sellTrade(TODAY, ORIGIN_TRADE_DATE);
		allocation = allocation(ORIGIN_TRADE_DATE, otherOriginTradeDate);

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.sameSessionCompleted()).isFalse();
		assertThat(response.holdHighPrice()).isNull();
		assertThat(response.holdHighAt()).isNull();
		assertThat(response.holdLowPrice()).isNull();
		assertThat(response.holdLowAt()).isNull();
		assertThat(response.sellVsHighRate()).isNull();
		assertThat(response.sellVsLowRate()).isNull();
		assertThat(response.buyToNewsMinutes()).isNull();
		assertThat(response.priceMoves()).isEmpty();
		verifyNoInteractions(stockReplayService, priceMoveEventRepository, priceMoveEventSourceRepository);
	}

	private PostSellFeedbackResponse getPostSellFeedback() {
		return stockPostSellFeedbackReader.read(trade, allocation);
	}

	private LocalTime capturedRevealCutoff() {
		ArgumentCaptor<LocalTime> captor = ArgumentCaptor.forClass(LocalTime.class);
		verify(priceMoveEventRepository)
			.findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				any(), any(), any(), any(), captor.capture());
		return captor.getValue();
	}

	private void givenSubSecondSell() {
		trade = subSecondSellTrade();
		allocation = subSecondAllocation();
	}

	private void givenSameSessionSell() {
		givenSameSessionSell(TODAY);
	}

	private void givenSameSessionSell(LocalDate serviceDate) {
		trade = sellTrade(serviceDate, ORIGIN_TRADE_DATE);
		allocation = allocation(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE);
	}

	private void givenCandles(List<StockCandleDto> candles) {
		when(stockReplayService.getFullDayCandles(INSTRUMENT_ID, ORIGIN_TRADE_DATE)).thenReturn(candles);
	}

	private PriceMoveEvent givenCards(PriceMoveEvent... cards) {
		when(priceMoveEventRepository
			.findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				any(), any(), any(), any(), any()))
			.thenReturn(List.of(cards));
		return cards.length == 0 ? null : cards[0];
	}

	private void givenSources(PriceMoveEvent card, MarketNewsItem newsItem) {
		givenSources(source(card, newsItem));
	}

	private void givenSources(PriceMoveEventSource... sources) {
		when(priceMoveEventSourceRepository.findAllByPriceMoveEventIdIn(any())).thenReturn(List.of(sources));
	}

	private static PriceMoveEventSource source(PriceMoveEvent card, MarketNewsItem newsItem) {
		return PriceMoveEventSource.of(card, newsItem);
	}

	private static StockCandleDto candle(LocalTime candleTime, String close) {
		BigDecimal price = new BigDecimal(close);
		return candle(candleTime, close, price.add(new BigDecimal("300")).toPlainString(),
			price.subtract(new BigDecimal("300")).toPlainString());
	}

	private static StockCandleDto candle(LocalTime candleTime, String close, String high, String low) {
		return new StockCandleDto(
			ORIGIN_TRADE_DATE, candleTime, new BigDecimal(close), new BigDecimal(high), new BigDecimal(low),
			new BigDecimal(close), 1_000L);
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
			stockInstrument(),
			MarketNewsItemType.NEWS,
			title,
			"hankyung.com",
			"https://news.example.test/" + title,
			publishedAt,
			publishedAt);
	}

	private static SellAllocationSummaryDto allocation(
		LocalDate earliestLotOriginTradeDate, LocalDate laterLotOriginTradeDate) {
		List<LocalDate> dates = new ArrayList<>();
		dates.add(earliestLotOriginTradeDate);
		dates.add(laterLotOriginTradeDate);
		return new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"),
			LocalDateTime.of(PAST_SERVICE_DATE, BUY_TIME),
			earliestLotOriginTradeDate,
			700_000L,
			105L,
			new BigDecimal("10"),
			dates);
	}

	private static Trade subSecondSellTrade() {
		return sellTrade(TODAY, ORIGIN_TRADE_DATE, SUB_SECOND_SELL_TIME);
	}

	private static SellAllocationSummaryDto subSecondAllocation() {
		return new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"),
			LocalDateTime.of(PAST_SERVICE_DATE, SUB_SECOND_BUY_TIME),
			ORIGIN_TRADE_DATE,
			700_000L,
			105L,
			new BigDecimal("10"),
			List.of(ORIGIN_TRADE_DATE));
	}

	private static Trade sellTrade(LocalDate serviceDate, LocalDate originTradeDate) {
		return sellTrade(serviceDate, originTradeDate, SELL_TIME);
	}

	private static Trade sellTrade(LocalDate serviceDate, LocalDate originTradeDate, LocalTime executedTime) {
		Instrument instrument = stockInstrument();
		LocalDateTime resolvedAt = LocalDateTime.of(serviceDate, LocalTime.of(8, 40));
		StockReplaySession session = StockReplaySession.ready(serviceDate, originTradeDate, resolvedAt, resolvedAt);
		LocalDateTime executedAt = LocalDateTime.of(serviceDate, executedTime);
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
