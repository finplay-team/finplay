// 매도 회고의 파생 사실(보유 구간 극값·buyToNewsMinutes 부호·카드 간격)과 게이트 상한 계산을 검증하는 단위 테스트다.
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
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItemResponse;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
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

// 이슈 #208 2번 항목이 소유한 완료 조건이다 — 파생 사실 정확 계산, 극값이 분봉 close 기준,
// sameSessionCompleted=false 조건의 파생 사실·priceMoves 절반. 게이트 ⑮ 자체는 저장된 TIME 값과 실제 서비스
// 날짜 위에서만 드러나므로 PostSellFeedbackGateIntegrationTest가 맡고, 여기서는 그 게이트에 넘기는
// 상한(revealCutoff)이 "그 체결의 서비스 날짜" 기준인지만 인자로 확인한다.
//
// 수치는 docs/api/feedback.md의 예시 그대로다 — 매도가 68,500, 극값 70,800(11:05)·68,100(14:20),
// sellVsHighRate −0.0325, sellVsLowRate 0.0059, 카드 11:20~11:25의 minutesAfterBuy 115·minutesBeforeSell 195,
// buyToNewsMinutes 105.
class PostSellFeedbackDerivedFactsTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long SELL_TRADE_ID = 2L;
	private static final Long INSTRUMENT_ID = 7L;

	// 원본 거래일과 서비스 날짜를 다르게 둔다 — 분봉·카드 조회에 서비스 날짜를 넘긴 구현이면 스텁이 안 맞아
	// 빈 결과가 되고 파생 사실이 전부 null이 된다(§C-5 — 두 날짜를 섞지 않는다).
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate TODAY = LocalDate.of(2026, 8, 5);
	private static final LocalDate PAST_SERVICE_DATE = LocalDate.of(2026, 8, 3);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	// 운영의 체결 시각 모양 — DATETIME(6)에 소수 초까지 찍힌다. 매도 초를 매수 초보다 작게 둬야
	// holdingMinutes에서도 절삭 유무가 갈린다(310 vs 309).
	private static final LocalTime SUB_SECOND_BUY_TIME = LocalTime.of(9, 30, 17, 400_000_000);
	private static final LocalTime SUB_SECOND_SELL_TIME = LocalTime.of(14, 40, 5);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	private static final LocalTime NOW_TIME = LocalTime.of(15, 0);

	private static final BigDecimal SELL_PRICE = new BigDecimal("68500");

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository = mock(
		PriceMoveEventSourceRepository.class);

	// 이 파일은 파생 사실(극값 등)만 본다 — 집단 비교는 stub하지 않고 Mockito 기본값(Optional.empty())으로 둔다.
	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

	// 기본 픽스처는 "그 체결의 서비스 날짜 = 오늘"이라 게이트 상한이 현재 시각(15:00)이다.
	private final Clock clock = Clock.fixed(TODAY.atTime(NOW_TIME).atZone(KST).toInstant(), KST);

	// 검증(404·403·400)과 배분 조회는 PostSellFeedbackContextReader로 옮겨 갔다(이슈 #282) — 이 파일은 이미
	// 검증을 마친 (trade, allocation)을 주식 조립에 그대로 넘겨 파생 사실만 본다.
	private final StockPostSellFeedbackReader stockPostSellFeedbackReader = new StockPostSellFeedbackReader(
		stockReplayService, priceMoveEventRepository,
		new PriceMoveSourceLoader(priceMoveEventSourceRepository), priceMovePeerStatRepository, clock);

	private Trade trade;

	private SellAllocationSummaryDto allocation;

	// --- 보유 구간 극값 (완료 조건 6번 — close 기준) ---

	// 극값 픽스처는 high/low가 close와 다른 봉을 반드시 포함한다 — 세 값이 같은 픽스처는 high를 쓴 구현에도
	// 초록이다(tasks.md 2번). 이 서비스의 시장가 체결은 직전 완료 분봉의 종가로만 이루어지므로 high로 극값을
	// 잡으면 사용자가 애초에 얻을 수 없었던 가격이 되고, 3번 항목이 그 값을 반사실 atHoldHigh에 그대로 올린다.
	@Test
	@DisplayName("극값은 분봉 close로 고른다 — high·low가 더 극단인 봉이 있어도 그 값을 쓰지 않는다")
	void picksHoldExtremesFromCandleCloseNotHighOrLow() {
		givenSameSessionSell();
		List<StockCandleDto> candles = List.of(
			// 구간 밖 — 클리핑하지 않은 구현이면 이 두 봉이 극값이 된다.
			candle(LocalTime.of(9, 20), "71000", "71200", "70900"),
			// close 69,500인데 high가 72,000이다 — high를 쓴 구현이면 여기가 최고가가 된다.
			candle(LocalTime.of(9, 30), "69500", "72000", "69400"),
			candle(LocalTime.of(11, 5), "70800", "71500", "70700"),
			// close 68,100인데 low가 67,500이다 — low를 쓴 구현이면 여기가 최저가가 된다.
			candle(LocalTime.of(14, 20), "68100", "68200", "67500"),
			candle(LocalTime.of(14, 40), "68500", "68600", "68000"),
			candle(LocalTime.of(14, 50), "67000", "67100", "66900"));
		givenCandles(candles);

		PostSellFeedbackResponse response = getPostSellFeedback();

		// 픽스처 자기검증 — high/low가 close와 다른 봉이 실제로 들어 있어야 두 구현이 갈린다.
		assertThat(candles).anySatisfy(candle -> {
			assertThat(candle.high()).isGreaterThan(candle.close());
			assertThat(candle.low()).isLessThan(candle.close());
		});

		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)));
		assertThat(response.holdLowPrice()).isEqualByComparingTo("68100");
		assertThat(response.holdLowAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 20)));
		// high/low를 쓴 구현이 내는 답 — 두 답이 갈리는 픽스처임을 남긴다.
		assertThat(response.holdHighPrice()).isNotEqualByComparingTo("72000");
		assertThat(response.holdLowPrice()).isNotEqualByComparingTo("67500");
		// 클리핑을 안 한 구현이 내는 답.
		assertThat(response.holdHighPrice()).isNotEqualByComparingTo("71000");
		assertThat(response.holdLowPrice()).isNotEqualByComparingTo("67000");

		// (매도가 − 기준가) ÷ 기준가, scale 4 HALF_UP. 계약 예시와 같은 값이다.
		assertThat(response.sellVsHighRate()).isEqualTo(new BigDecimal("-0.0325"));
		assertThat(response.sellVsLowRate()).isEqualTo(new BigDecimal("0.0059"));
	}

	@Test
	@DisplayName("보유 구간은 양 끝을 포함한다 — 매수 분·매도 분의 봉도 극값 후보다")
	void includesTheCandlesAtBothBoundariesOfTheHoldPeriod() {
		givenSameSessionSell();
		givenCandles(List.of(
			candle(LocalTime.of(9, 29), "80000", "80000", "80000"),
			// 매수 분 봉이 최고가, 매도 분 봉이 최저가다 — 경계를 배타로 자른 구현이면 둘 다 빠진다.
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

	// 같은 종가가 여러 번 나온 날 뒤쪽 시각을 고르면 "하락이 시작되기 몇 분 전"류의 서술 근거가 실행마다 달라진다.
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

	// 극값 여섯 값은 함께 있거나 함께 없다 — "극값은 있는데 비율만 빈" 조합이 응답에 나가면 화면이 극값 시각은
	// 그리면서 매도가와의 거리는 못 그린다.
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
		// 수치 요약은 그대로 나간다.
		assertThat(response.sellPrice()).isEqualByComparingTo(SELL_PRICE);
	}

	// 분봉은 원본 거래일로 읽는다 — 서비스 날짜로 물으면 그 날짜의 분봉이 없어 예외도 로그도 없이 극값이 빈다.
	@Test
	@DisplayName("분봉을 원본 거래일로 읽는다 — 서비스 날짜가 아니다")
	void readsCandlesByOriginTradeDateNotServiceDate() {
		givenSameSessionSell();
		givenCandles(List.of(candle(LocalTime.of(11, 5), "70800", "70800", "70800")));

		getPostSellFeedback();

		verify(stockReplayService).getFullDayCandles(INSTRUMENT_ID, ORIGIN_TRADE_DATE);
	}

	// --- 보유 구간 카드의 간격 (완료 조건 5번) ---

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
			// 구간에는 카드의 원본 거래일이 붙는다 — 조회한 날짜가 아니다.
			assertThat(move.windowStart()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 20)));
			assertThat(move.windowEnd()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 25)));
			assertThat(move.changeRate()).isEqualByComparingTo("-0.018200");
			assertThat(move.minutesAfterBuy()).isEqualTo(115);
			assertThat(move.minutesBeforeSell()).isEqualTo(195);
			// windowStart로 잰 구현이 내는 답 — 두 답이 갈리는 픽스처임을 남긴다.
			assertThat(move.minutesAfterBuy()).isNotEqualTo(110);
			assertThat(move.minutesBeforeSell()).isNotEqualTo(200);
			assertThat(move.narrative()).isNotEmpty();
			assertThat(move.sources()).extracting(NewsItem::title).containsExactly("생산 차질");
		});
	}

	// 파인더에 넘기는 구간이 windowEnd 범위이고 보유 구간의 두 시각 그대로여야 한다 — 파인더 자체의 좁히기는
	// PriceMoveEventRepositoryTest가 실제 행으로 본다.
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
		// 파인더가 발행시각 내림차순 + id 오름차순으로 돌려주므로 조립도 그 순서를 보존해야 한다.
		givenSources(
			source(earlier, news("늦은 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 24)))),
			source(earlier, news("이른 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)))),
			source(later, news("오후 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(13, 20)))));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.priceMoves())
			.extracting(HeldPriceMoveItemResponse::id, move -> move.windowStart().toLocalTime())
			.containsExactly(tuple(12L, LocalTime.of(11, 20)), tuple(13L, LocalTime.of(13, 20)));
		assertThat(response.priceMoves().get(0).sources())
			.extracting(NewsItem::title)
			.containsExactly("늦은 기사", "이른 기사");
		assertThat(response.priceMoves().get(1).sources())
			.extracting(NewsItem::title)
			.containsExactly("오후 기사");
	}

	// --- buyToNewsMinutes (완료 조건 5번의 부호) ---

	@Test
	@DisplayName("매수가 기사보다 앞서면 buyToNewsMinutes가 양수다 — 09:30 매수·11:15 기사면 105다")
	void buyToNewsMinutesIsPositiveWhenTheBuyCameBeforeTheArticle() {
		givenSameSessionSell();
		givenCandles(List.of());
		PriceMoveEvent card = givenCards(card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25)));
		givenSources(card, news("생산 차질", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15))));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.buyToNewsMinutes()).isEqualTo(105);
		// 부호를 뒤집은 구현이 내는 답 — 화면이 "기사를 보고 매수했다"와 정확히 거꾸로 말한다.
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

		// 10:00 − 09:30 = 30분. 첫 카드의 기사만 본 구현이면 105가 된다.
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

	// 모수가 게이트를 통과한 카드의 근거로 한정된다 — 감춰진 카드의 기사를 넣으면 분 수 하나로 그 기사의 존재와
	// 시각이 새어 나간다. 근거 조회 자체가 노출된 카드 id로만 나가는지를 인자로 확인한다.
	@Test
	@DisplayName("근거는 게이트를 통과한 카드 id로만 조회한다 — 감춰진 카드의 기사가 buyToNewsMinutes에 섞이지 않는다")
	void asksSourcesOnlyForRevealedCardIds() {
		givenSameSessionSell();
		givenCandles(List.of());
		PriceMoveEvent revealed = card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25));
		givenCards(revealed);
		// 감춰진 카드(id 99)의 근거는 09:00 기사다 — 모수에 섞이면 buyToNewsMinutes가 −30으로 새어 나간다.
		givenSources(
			source(revealed, news("생산 차질", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)))));

		PostSellFeedbackResponse response = getPostSellFeedback();

		ArgumentCaptor<List<Long>> captor = ArgumentCaptor.captor();
		verify(priceMoveEventSourceRepository).findAllByPriceMoveEventIdIn(captor.capture());
		assertThat(captor.getValue()).containsExactly(12L).doesNotContain(99L);
		assertThat(response.buyToNewsMinutes()).isEqualTo(105);
	}

	// --- 소수 초 체결시각 (2026-08-05 reviewer 차단 지적) ---
	//
	// 운영에서 trades.executed_at은 DATETIME(6)이고 OrderExecutionService가 LocalDateTime.now(clock)으로 찍어
	// 09:30:17.4xxxxx 꼴이다. 반면 candle_time·window_end는 정시다. 그래서 경계를 체결시각 그대로 쓰면
	// 하한만 매수 분봉을 탈락시키고 상한은 매도 분봉을 포함하는 비대칭이 생기고, Duration.toMinutes()의 0 방향
	// 절삭이 분 단위 값을 한 칸 줄인다. **정시 픽스처로는 맞는 구현과 틀린 구현이 같은 답을 낸다.**

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

		// 픽스처 자기검증 — 체결 시각에 초·소수 초가 실제로 붙어 있어야 두 구현이 갈린다.
		assertThat(response.buyAt().getNano()).isNotZero();
		assertThat(response.sellAt().getSecond()).isNotZero();
		// 응답의 buyAt·sellAt은 계약이 정한 체결 시각이라 초를 그대로 싣는다 — 내리는 것은 비교용 경계뿐이다.
		assertThat(response.buyAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SUB_SECOND_BUY_TIME));
		assertThat(response.sellAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SUB_SECOND_SELL_TIME));

		// 계약 예시 그대로다. 괄호 안은 Duration.between(...).toMinutes()를 쓴 구현이 내는 답이다.
		assertThat(response.holdingMinutes()).isEqualTo(310).isNotEqualTo(309);
		assertThat(response.priceMoves()).singleElement().satisfies(move -> {
			assertThat(move.minutesAfterBuy()).isEqualTo(115).isNotEqualTo(114);
			assertThat(move.minutesBeforeSell()).isEqualTo(195);
		});
		assertThat(response.buyToNewsMinutes()).isEqualTo(105).isNotEqualTo(104);
	}

	// 경계를 분으로 내리지 않은 구현은 09:30 분봉을 하한에서 탈락시켜 극값이 한 봉 밀린다 — 그러면 3번 항목의
	// 반사실 atHoldHigh까지 함께 틀린다.
	@Test
	@DisplayName("매수 분봉이 보유 구간 최고가면 초가 붙은 체결시각에도 그 봉이 잡힌다")
	void keepsTheBuyMinuteCandleAsHoldHighWithSubSecondExecutionTimes() {
		givenSubSecondSell();
		givenCandles(List.of(
			// 매수 분봉이 그날 보유 구간 최고 종가다.
			candle(LocalTime.of(9, 30), "70800"),
			candle(LocalTime.of(9, 31), "69000"),
			candle(LocalTime.of(11, 5), "69500"),
			candle(SELL_TIME, "68100")));
		givenCards();

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)));
		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		// 하한을 내리지 않은 구현이 내는 답 — 09:30 봉이 빠져 09:31 이후에서 최고가를 고른다.
		assertThat(response.holdHighAt()).isNotEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)));
		assertThat(response.holdHighPrice()).isNotEqualByComparingTo("69500");
		// 매도 분봉은 포함이므로 최저가가 그 봉이다 — 상한·하한이 같은 규칙으로 내려간다는 확인이다.
		assertThat(response.holdLowAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME));
		assertThat(response.sellVsHighRate()).isEqualTo(new BigDecimal("-0.0325"));
	}

	// 0 방향 절삭은 부호에 따라 방향이 뒤집힌다 — 음수에서는 값을 키운다. 양수 케이스만 보면 이 자리를 못 잡는다.
	@Test
	@DisplayName("기사가 매수보다 이르면 초가 붙은 체결시각에도 분 수가 절삭 방향에 흔들리지 않는다")
	void keepsNegativeBuyToNewsMinutesExactWithSubSecondExecutionTimes() {
		givenSubSecondSell();
		givenCandles(List.of());
		PriceMoveEvent card = givenCards(card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25)));
		// 08:59:50 기사 → 09:30 매수. 분으로 내리면 08:59 → 09:30이라 −31분이다.
		givenSources(card, news("장 전 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(8, 59, 50))));

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.buyToNewsMinutes()).isEqualTo(-31);
		// Duration.between(09:30:17.4, 08:59:50) = −30분 27.4초 → 0 방향 절삭으로 −30이 되는 구현이 내는 답.
		assertThat(response.buyToNewsMinutes()).isNotEqualTo(-30);
	}

	// --- 게이트 상한 (게이트 ⑮의 기준 날짜) ---

	// PriceMoveQueryService를 그대로 본떠 LocalTime.now(clock)을 넘기면, 어제 판 체결을 오늘 오전에 조회할 때
	// 그날 오후 카드가 다시 감춰진다 — 게이트 ⑭이 매도 후 흐름에 대해 막는 것과 같은 되돌림이다.
	// 상한이 "그날 끝"인지만 본다 — 어떤 값으로 그날 끝을 표현하는지는 이 단정이 고정하지 않는다.
	// LocalTime.MAX는 MySQL TIME 파라미터로 살아남지 못해 카드가 0건이 되며, 그 자리는
	// PriceMoveEventRepositoryTest가 실제 쿼리로 못박는다.
	@Test
	@DisplayName("과거 서비스 날짜의 체결이면 게이트 상한이 그날 끝이다 — 오늘 벽시계가 아니다")
	void usesEndOfDayCutoffForATradeFromAPastServiceDate() {
		givenSameSessionSell(PAST_SERVICE_DATE);
		givenCandles(List.of());
		givenCards();

		getPostSellFeedback();

		assertThat(capturedRevealCutoff()).isAfterOrEqualTo(LocalTime.of(23, 59, 59));
		// 오늘 벽시계를 그대로 넘긴 구현이 내는 값 — 두 값이 갈리는 픽스처임을 남긴다.
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

	// 서비스 날짜가 미래인 체결은 원장에 생기지 않지만, 생기면 그날 카드가 전부 열린 상태로 나간다.
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

	// --- sameSessionCompleted=false (완료 조건 4번의 2번 항목 몫) ---

	// 분봉이 불연속이라 계산 자체가 성립하지 않는다 — 그래서 값을 비우는 것에 그치지 않고 협력자를 부르지도 않는다.
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

	// --- 픽스처 ---

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

	/** 초·소수 초가 붙은 체결시각 픽스처 — 원본 거래일은 매도와 같아 sameSessionCompleted가 참이다. */
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

	// 원본 거래일로만 스텁한다 — 서비스 날짜로 물은 구현은 스텁이 없어 빈 목록을 받는다.
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
