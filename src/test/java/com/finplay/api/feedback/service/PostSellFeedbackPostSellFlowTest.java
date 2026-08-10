// 매도 후 흐름·반사실 가격과 장 마감 게이트(§C-5)를 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventSource;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.feedback.dto.response.Counterfactuals;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.dto.response.PostSellFlow;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.service.StockCandleDto;
import com.finplay.api.market.service.StockReplayService;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.service.SellAllocationSummaryDto;
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

// 이슈 #208 3번 항목이 소유한 완료 조건이다 — atClose·closePrice가 마지막 분봉, 게이트 ⑬(직전·직후),
// 게이트 ⑭(날짜를 하루 넘긴 조회), sameSessionCompleted=false 조건의 반사실·집단 비교. 실제 분봉·시계 위의
// 종단은 PostSellFeedbackGateIntegrationTest가, 직렬화 필드 집합은 PostSellFeedbackControllerTest가 맡는다.
//
// 픽스처는 docs/api-contracts.md의 예시를 그대로 재현한다 — 매도 68,500(14:40), 극값 70,800(11:05)·68,100(14:20),
// closePrice 69,200, sellToCloseRate 0.0102, postSellHigh 69,500(15:05), atFirstMoveAfterBuy 69,300(11:25).
// 그날 마지막 분봉은 15:27이다: 15:30 봉이 오는 것은 보장되지 않아(§C-2-1) 리터럴 15:30으로 찾는 구현이면
// closePrice·atClose가 예외도 없이 빈다.
class PostSellFeedbackPostSellFlowTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long SELL_TRADE_ID = 2L;
	private static final Long INSTRUMENT_ID = 7L;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate TRADE_SERVICE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	// 그날 마지막 분봉 — 15:30이 아니다.
	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);

	// 장 마감 게이트의 세 조회 시각. 직전·직후는 같은 서비스 날짜이고, 셋째는 날짜를 하루 넘긴 조회다.
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

	// 이 파일은 매도 후 흐름 게이트만 본다 — 집단 비교는 stub하지 않고 Mockito 기본값(Optional.empty())으로 둔다.
	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

	// 검증(404·403·400)과 배분 조회는 PostSellFeedbackContextReader로 옮겨 갔다(이슈 #282) — 이 파일은 이미
	// 검증을 마친 (trade, allocation)을 주식 조립에 그대로 넘겨 매도 후 흐름 게이트만 본다.
	private Trade trade;

	private SellAllocationSummaryDto allocation;

	// --- 게이트 ⑬ 직전·직후 (완료 조건 13번) ---

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

		// 게이트가 가리는 것은 매도 이후 가격뿐이다 — 보유 구간 파생 사실은 그대로 나간다.
		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdLowPrice()).isEqualByComparingTo("68100");
	}

	// 경계가 >= 이므로 정각에 열린다. 부등호가 > 로 바뀌면 1분(또는 1초) 늦게 열리고 아무도 모른다.
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

	// getFullDayCandles는 하루치를 그대로 주므로(§C-6) 노출을 가르는 것은 조회 횟수가 아니라 "어디까지 잘라
	// 쓰는가"다. 매도 이후 분봉을 그날 최고 종가로 두면, 게이트 전에 그 구간을 읽은 구현에서 99,000이 응답
	// 어딘가(매도 후 흐름·반사실·보유 구간 극값)에 나타난다.
	@Test
	@DisplayName("게이트 전에는 매도 이후 분봉 값이 응답 어디에도 나타나지 않는다")
	void neverExposesAnyPostSellCandleValueBeforeTheGate() {
		givenSameSessionSell();
		givenCandles(List.of(
			candle(BUY_TIME, "69500"),
			candle(LocalTime.of(11, 5), "70800"),
			candle(SELL_TIME, "68500"),
			// 매도 이후 구간에 그날 최고 종가를 둔다 — 새면 반드시 극값이나 매도 후 흐름에 잡힌다.
			candle(LocalTime.of(15, 5), "99000"),
			candle(LAST_CANDLE_TIME, "98000")));
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(JUST_BEFORE_GATE);

		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)));
		assertThat(response.postSellFlow().closePrice()).isNull();
		assertThat(response.postSellFlow().postSellHighPrice()).isNull();
		assertThat(response.counterfactuals().atClose()).isNull();
		// 게이트가 열리면 같은 픽스처에서 그 값이 실제로 나온다 — 위 단정이 "값이 애초에 없어서" 통과한 것이
		// 아님을 짝으로 확인한다.
		PostSellFeedbackResponse afterGate = getPostSellFeedbackAt(EXACTLY_AT_GATE);
		assertThat(afterGate.postSellFlow().postSellHighPrice()).isEqualByComparingTo("99000");
	}

	// --- 게이트 ⑭ 날짜를 하루 넘긴 조회 (완료 조건 14번) ---

	// "오늘 15:30"으로 잡은 구현이면 다음 날 10:00은 아직 마감 전이라 어제 READY였던 값이 NOT_YET으로
	// 되돌아간다. 같은 날 조회만 재현하면 두 구현이 같은 답을 내므로 이 케이스가 그 분기의 유일한 검증 수단이다.
	@Test
	@DisplayName("전날 매도 건을 다음 날 오전 10:00에 조회해도 READY를 유지한다")
	void keepsReadyWhenAYesterdayTradeIsViewedDuringTheNextTradingSession() {
		givenSameSessionSell();
		givenCandles(contractExampleCandles());
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(NEXT_DAY_MORNING);

		// 픽스처 자기검증 — 조회 시각이 그날 15:30 이전이라 "오늘 15:30" 구현이면 닫혀 있어야 하는 상태다.
		assertThat(NEXT_DAY_MORNING.toLocalTime()).isBefore(LocalTime.of(15, 30));
		assertThat(NEXT_DAY_MORNING.toLocalDate()).isAfter(TRADE_SERVICE_DATE);

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("69200");
	}

	// --- 마지막 분봉 (완료 조건 7번) ---

	@Test
	@DisplayName("closePrice·closeAt과 atClose가 그 거래일 마지막 분봉(15:27)의 close다 — 리터럴 15:30이 아니다")
	void usesTheLastCandleOfTheTradingDayForCloseAndAtCloseScenario() {
		givenSameSessionSell();
		List<StockCandleDto> candles = contractExampleCandles();
		givenCandles(candles);
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);

		// 픽스처 자기검증 — 15:30 분봉이 없는 날이어야 두 구현이 갈린다.
		assertThat(candles).extracting(StockCandleDto::candleTime).doesNotContain(LocalTime.of(15, 30));

		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("69200");
		assertThat(response.postSellFlow().closeAt())
			.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LAST_CANDLE_TIME));
		assertThat(response.counterfactuals().atClose().price()).isEqualByComparingTo("69200");
		assertThat(response.counterfactuals().atClose().at())
			.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LAST_CANDLE_TIME));
	}

	// --- status는 게이트만 반영한다 ---

	// 없는 데이터를 NOT_YET으로 감추면 장 마감 뒤에도 영원히 "아직"으로 보인다. 바로 위 테스트(분봉이 있는 경우)와
	// 짝이며, 한쪽만 재현하면 status를 데이터 유무로 판정한 구현이 걸리지 않는다.
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

	// --- sellToCloseRate 부호 ---

	// rateAgainst가 sellVsHighRate와 sellToCloseRate 양쪽에 쓰이는데 기준가 자리가 뒤바뀐다. 인자 순서를 헷갈리면
	// 부호만 반대인 값이 예외도 로그도 없이 나가므로, 한 응답에서 두 비율의 부호가 서로 다른 픽스처로 겨눈다.
	@Test
	@DisplayName("sellToCloseRate는 (종가 − 매도가) ÷ 매도가다 — 종가가 높으면 양수이고 sellVsHighRate와 부호가 갈린다")
	void computesSellToCloseRateAgainstTheSellPriceNotTheClosePrice() {
		givenSameSessionSell();
		givenCandles(contractExampleCandles());
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);

		// (69,200 − 68,500) ÷ 68,500 = 0.010218… → scale 4 HALF_UP. 계약 예시와 같은 값이다.
		assertThat(response.postSellFlow().sellToCloseRate()).isEqualTo(new BigDecimal("0.0102"));
		// 기준가 자리를 뒤바꾼 구현이 내는 답 — (68,500 − 69,200) ÷ 69,200 = −0.0101이다.
		assertThat(response.postSellFlow().sellToCloseRate()).isNotEqualTo(new BigDecimal("-0.0101"));
		// 같은 응답에서 극값 비율은 매도가가 분자에 오므로 부호가 반대다.
		assertThat(response.sellVsHighRate()).isEqualTo(new BigDecimal("-0.0325"));
		assertThat(response.postSellFlow().sellToCloseRate().signum())
			.isNotEqualTo(response.sellVsHighRate().signum());
	}

	// --- postSellHigh 경계 ---

	// 극값이 매도 분봉을 포함(양 끝 포함)하므로 이쪽은 배타여야 한다 — 같은 분봉이 "보유 중 최고가"와 "매도 후
	// 최고가"에 동시에 잡히면 화면이 두 값을 나란히 놓는 의미가 없어진다.
	@Test
	@DisplayName("매도 분봉이 그날 최고 종가여도 postSellHigh에 잡히지 않는다 — 경계가 배타다")
	void excludesTheSellMinuteCandleFromThePostSellHigh() {
		givenSameSessionSell();
		givenCandles(List.of(
			candle(BUY_TIME, "68000"),
			// 매도 분봉이 그날 최고 종가다.
			candle(SELL_TIME, "70000"),
			candle(LocalTime.of(15, 5), "69500"),
			candle(LAST_CANDLE_TIME, "69200")));
		givenNoCards();

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);

		assertThat(response.holdHighPrice()).isEqualByComparingTo("70000");
		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME));
		// 경계를 포함으로 바꾼 구현이 내는 답 — 70,000이 두 값에 동시에 잡힌다.
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

	// --- 반사실 3종 ---

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
		// 계약 예시 — 보유 구간 첫 카드의 windowEnd 11:25 분봉 종가다.
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

	// 가격을 지어내지 않는다 — 그 카드의 windowEnd 분봉이 픽스처에 없으면 null이다.
	@Test
	@DisplayName("첫 카드의 windowEnd 분봉이 없으면 atFirstMoveAfterBuy가 null이다")
	void leavesAtFirstMoveAfterBuyNullWhenThatWindowEndHasNoCandle() {
		givenSameSessionSell();
		givenCandles(contractExampleCandles());
		// 11:26 분봉은 픽스처에 없다.
		PriceMoveEvent card = givenCards(card(12L, LocalTime.of(11, 21), LocalTime.of(11, 26)));
		givenSources(card, news("생산 차질", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15))));

		Counterfactuals counterfactuals = getPostSellFeedbackAt(EXACTLY_AT_GATE).counterfactuals();

		assertThat(counterfactuals.atFirstMoveAfterBuy()).isNull();
	}

	// --- 이슈 #212 1번이 채운 반사실 returnRate — peerComparison은 여전히 4번 항목이 끼울 자리다 ---

	// 이 파일의 픽스처(contractExampleCandles·card)는 docs/api-contracts.md 예시와 값이 같다 — atClose 69,200,
	// atHoldHigh 70,800, atFirstMoveAfterBuy 69,300, buyBasis 700,105(=700,000+105), quantity 10. 값이 예시와
	// 일치하는지는 여기서 보고, FLOOR와 HALF_UP이 실제로 갈리는 함정 픽스처는
	// PostSellFeedbackCounterfactualReturnRateTest가 따로 본다(이 픽스처의 buyBasis는 수수료 1원 차를 4번째
	// 소수점까지 못 밀어 올려 그 함정을 못 잡는다).
	@Test
	@DisplayName("반사실 3종의 returnRate가 수수료를 다시 계산해 api-contracts.md 예시 값 그대로 나온다 — peerComparison은 여전히 NOT_YET이다")
	void computesCounterfactualReturnRatesMatchingTheContractExampleWhilePeerComparisonStaysForTheNextItem() {
		givenSameSessionSell();
		givenCandles(contractExampleCandles());
		PriceMoveEvent card = givenCards(card(12L, LocalTime.of(11, 20), LocalTime.of(11, 25)));
		givenSources(card, news("생산 차질", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15))));

		PostSellFeedbackResponse response = getPostSellFeedbackAt(EXACTLY_AT_GATE);

		// (69,200×10 − FLOOR(69,200×10×0.00015)) − 700,105 = 691,897 − 700,105 = −8,208 → −8,208÷700,105 → −0.0117.
		assertThat(response.counterfactuals().atClose().returnRate()).isEqualTo(new BigDecimal("-0.0117"));
		assertThat(response.counterfactuals().atHoldHigh().returnRate()).isEqualTo(new BigDecimal("0.0111"));
		assertThat(response.counterfactuals().atFirstMoveAfterBuy().returnRate()).isEqualTo(new BigDecimal("-0.0103"));
		// peerComparison은 이 이슈(#212)의 4번 항목 몫이라 여기서는 여전히 NOT_YET 상수 껍데기다. 기준 카드를 이미
		// 알고 있어도(반사실 atFirstMoveAfterBuy와 같은 카드다) priceMoveId를 채우지 않는다 — 4번이 판정을 붙이는
		// 순간 값 → null로 사라지는 조합이 생기지 않게 한다.
		assertThat(response.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(response.peerComparison().priceMoveId()).isNull();
		assertThat(response.peerComparison().holderCount()).isNull();
		assertThat(response.peerComparison().soldWithin30MinRate()).isNull();
		assertThat(response.peerComparison().medianMinutesToSell()).isNull();
		assertThat(response.peerComparison().yourMinutesToSell()).isNull();
		assertThat(response.priceMoves()).isNotEmpty();
	}

	// --- sameSessionCompleted=false (완료 조건 4번의 3번 항목 몫) ---

	// status만 담은 껍데기를 내리지 않는다 — 세 필드가 자기 자신이 null이다.
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

	// --- 픽스처 ---

	private PostSellFeedbackResponse getPostSellFeedbackAt(LocalDateTime now) {
		StockPostSellFeedbackReader reader = new StockPostSellFeedbackReader(
			stockReplayService, priceMoveEventRepository,
			new PriceMoveSourceLoader(priceMoveEventSourceRepository), priceMovePeerStatRepository,
			Clock.fixed(now.atZone(KST).toInstant(), KST));
		return reader.read(trade, allocation);
	}

	/** 계약 예시를 그대로 재현하는 하루치 분봉. 마지막 분봉이 15:27이고 15:30 분봉은 없다. */
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
		// high·low를 close와 다르게 둔다 — 극값·반사실이 close만 쓰는지 이 파일의 픽스처에서도 유지한다.
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
		Account account = Account.create(user, com.finplay.api.account.domain.Market.STOCK, executedAt);
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
