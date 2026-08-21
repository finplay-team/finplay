// 고정 Clock + 실 MySQL로 매도 회고의 카드 노출 게이트(⑮)와 실제 분봉 기반 보유 구간 극값을 검증한다.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItemResponse;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
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

// 이슈 #208 2번 항목의 게이트 ⑮와 극값 close 기준이 목표다. 게이트는 저장된 TIME 값과 그 체결의 서비스 날짜를
// 함께 봐야 드러나므로 mock으로 끝내지 않는다(ADR-0003).
//
// 핵심은 상한 계산이다 — PriceMoveQueryService처럼 LocalTime.now(clock)을 그대로 상한으로 쓰면 어제 판 체결을
// 오늘 오전에 조회할 때 그날 오후 카드가 다시 감춰진다. 같은 픽스처를 같은 날 11:40과 다음 날 10:00에서 두 번
// 조회해 두 구현을 갈라 놓는다.
//
// 공유 컨테이너를 더럽히지 않도록 클래스 트랜잭션으로 감싼다 (PriceMoveQueryGateIntegrationTest 선례).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class PostSellFeedbackGateIntegrationTest {

	// 2026-08-04(화)에 2026-07-29를 재생하던 중 매매가 완결됐다. 조회는 그날과 다음 날 두 번 한다.
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	// 서비스 날짜는 stock_replay_sessions의 UNIQUE(service_date)에 걸린다. 공유 Testcontainer에 트랜잭션 없이
	// 커밋하는 테스트(CandleQueryServiceIntegrationTest가 2026-08-04·08-05를 커밋한다)와 같은 날짜를 쓰면
	// 단독 실행은 통과하고 `./gradlew build` 전체에서만 Duplicate entry로 깨진다 — 그래서 이 파일 전용
	// 연도(2031)를 쓴다. 원본 거래일은 UNIQUE 대상이 아니라 그대로 둔다.
	private static final LocalDate TRADE_SERVICE_DATE = LocalDate.of(2031, 8, 4);
	private static final LocalDate NEXT_DAY = LocalDate.of(2031, 8, 5);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(11, 30);
	// 그날 마지막 분봉 — 15:30이 아니다(§C-2-1). 리터럴 15:30으로 찾는 구현이면 closePrice·atClose가 빈다.
	private static final LocalTime LAST_CANDLE_TIME = LocalTime.of(15, 27);

	// 매도 직후(같은 서비스 날짜)와 다음 날 오전의 두 조회 시각.
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
		// V7 시드 심볼과 겹치지 않는 테스트 전용 심볼 — UNIQUE(symbol) 충돌 방지.
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

	// --- 게이트 ⑮ (완료 조건 15번) ---

	// 카드 B의 근거 기사가 11:50에 발행돼 revealTime이 11:55로 밀렸다 — 보유 구간(09:30~11:30)이 이미 지난 카드라도
	// 근거 기사는 windowEnd 이후에 붙을 수 있어 게이트가 필요한 자리다. 이것을 매도 회고에서 먼저 보여주면
	// Part A 카드 목록·Part C 기사 목록보다 앞선다.
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

	// 이 파일의 핵심 — 과거 서비스 날짜 체결을 오늘 오전에 조회하는 경로다. LocalTime.now(clock)(10:00)을 상한으로
	// 쓴 구현이면 revealTime 11:55 카드가 다시 감춰진다(게이트 ⑭과 같은 되돌림). 같은 날 조회만 재현하면 두
	// 구현이 같은 답을 낸다.
	@Test
	@DisplayName("다음 날 오전 10:00에 조회해도 그 서비스 날짜의 오후 카드가 계속 보인다")
	void keepsCardsFromAPastServiceDateOpenWhenViewedOnALaterMorning() {
		saveRevealedCard();
		saveHiddenCard();
		mutableClock.set(NEXT_DAY_VIEW);

		PostSellFeedbackResponse response = getPostSellFeedback();

		// 픽스처 자기검증 — 조회 시각(10:00)이 감춰졌던 카드의 revealTime(11:55)보다 이르다.
		assertThat(NEXT_DAY_VIEW.toLocalTime()).isBefore(LocalTime.of(11, 55));
		assertThat(response.priceMoves())
			.extracting(move -> move.windowEnd().toLocalTime())
			.containsExactly(LocalTime.of(10, 25), LocalTime.of(11, 25));
	}

	// buyToNewsMinutes의 모수가 게이트를 통과한 카드의 근거로 한정된다 — 감춰진 카드의 09:00 기사를 넣으면 분 수
	// 하나로 그 기사의 존재와 시각이 새어 나간다. 두 조회가 짝이다: 같은 서비스 날짜 12:00(revealTime 11:55 이후)에는
	// 그 09:00 기사가 실제로 모수에 들어오므로, 11:40 단정이 "기사가 애초에 없어서" 통과한 것이 아님이 확인된다.
	@Test
	@DisplayName("감춰진 카드의 이른 기사는 buyToNewsMinutes에 섞이지 않고, 그 카드가 열리면 값이 바뀐다")
	void excludesHiddenCardSourcesFromBuyToNewsMinutesUntilThatCardOpens() {
		saveRevealedCard();
		saveHiddenCard();

		mutableClock.set(SAME_DAY_VIEW);
		PostSellFeedbackResponse beforeReveal = getPostSellFeedback();
		// 노출된 카드의 근거 10:15 − 매수 09:30 = 45분.
		assertThat(beforeReveal.buyToNewsMinutes()).isEqualTo(45);
		// 감춰진 카드의 09:00 기사가 섞인 구현이 내는 값.
		assertThat(beforeReveal.buyToNewsMinutes()).isNotEqualTo(-30);
		assertThat(beforeReveal.priceMoves()).flatExtracting(HeldPriceMoveItemResponse::sources)
			.extracting(NewsItem::title)
			.doesNotContain("장 초반 기사");

		// 같은 서비스 날짜 12:00 — revealTime 11:55가 지나 그 카드가 열린다.
		mutableClock.set(LocalDateTime.of(TRADE_SERVICE_DATE, LocalTime.of(12, 0)));
		PostSellFeedbackResponse afterReveal = getPostSellFeedback();

		// 09:00 기사가 모수에 들어오면 09:00 − 09:30 = −30분이다 — 부호도 함께 확인된다.
		assertThat(afterReveal.buyToNewsMinutes()).isEqualTo(-30);
		assertThat(afterReveal.priceMoves()).flatExtracting(HeldPriceMoveItemResponse::sources)
			.extracting(NewsItem::title)
			.contains("장 초반 기사");
	}

	// --- 카드 간격·정렬 ---

	@Test
	@DisplayName("카드 간격은 windowEnd 기준이고 windowStart 동률은 id로 가르며 근거는 발행시각 내림차순이다")
	void computesIntervalsFromWindowEndAndKeepsContractOrdering() {
		PriceMoveEvent intraday = saveRevealedCard();
		// 첫 분봉이 09:00인 날 갭 카드와 장중 첫 후보의 windowStart가 정확히 같아진다 (§데이터 모델).
		PriceMoveEvent gap = saveCard(
			PriceMoveEventType.OPENING_GAP, LocalTime.of(10, 20), LocalTime.of(10, 20), LocalTime.of(9, 0));
		saveSource(gap, saveNews("갭 근거 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(8, 40))));
		saveSource(intraday, saveNews("늦은 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 24))));
		mutableClock.set(SAME_DAY_VIEW);

		PostSellFeedbackResponse response = getPostSellFeedback();

		// 픽스처 자기검증 — 두 windowStart가 실제로 같고 저장 순서가 장중 → 갭이다.
		assertThat(intraday.getWindowStart()).isEqualTo(gap.getWindowStart());
		assertThat(intraday.getId()).isLessThan(gap.getId());

		assertThat(response.priceMoves())
			.extracting(HeldPriceMoveItemResponse::id, HeldPriceMoveItemResponse::minutesAfterBuy,
				HeldPriceMoveItemResponse::minutesBeforeSell)
			.containsExactly(
				// 장중 카드 windowEnd 10:25 — 매수 55분 뒤, 매도 65분 전.
				tuple(intraday.getId(), 55, 65),
				// 갭 카드 windowEnd 10:20 — windowStart 동률이라 id 순서로 뒤에 온다.
				tuple(gap.getId(), 50, 70));
		// 각 카드의 근거는 발행시각 내림차순이다 (계약).
		assertThat(response.priceMoves().get(0).sources())
			.extracting(NewsItem::title)
			.containsExactly("늦은 기사", "10:15 기사");
	}

	// --- 보유 구간 극값 (완료 조건 6번) ---

	// high/low가 close와 다른 봉을 반드시 포함한다 — 세 값이 같은 픽스처는 high를 쓴 구현에도 초록이다.
	@Test
	@DisplayName("실제 분봉에서 극값을 close로 고르고 보유 구간 밖 분봉은 쓰지 않는다")
	void picksHoldExtremesFromRealCandleClosesWithinTheHoldPeriod() {
		// 구간 밖 — 클리핑하지 않은 구현이면 이 두 봉이 극값이 된다.
		saveCandle(LocalTime.of(9, 20), "71000", "71200", "70900");
		// close 69,500인데 high가 72,000이다.
		saveCandle(BUY_TIME, "69500", "72000", "69400");
		saveCandle(LocalTime.of(10, 30), "70800", "71500", "70700");
		// close 68,100인데 low가 67,500이다.
		saveCandle(LocalTime.of(11, 20), "68100", "68200", "67500");
		saveCandle(SELL_TIME, "68500", "68600", "68000");
		saveCandle(LocalTime.of(11, 40), "67000", "67100", "66900");
		mutableClock.set(SAME_DAY_VIEW);

		PostSellFeedbackResponse response = getPostSellFeedback();

		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.holdHighAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 30)));
		assertThat(response.holdLowPrice()).isEqualByComparingTo("68100");
		assertThat(response.holdLowAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 20)));
		// high/low를 쓴 구현과 클리핑을 안 한 구현이 내는 답 — 두 답이 갈리는 픽스처임을 남긴다.
		assertThat(response.holdHighPrice())
			.isNotEqualByComparingTo("72000")
			.isNotEqualByComparingTo("71000");
		assertThat(response.holdLowPrice())
			.isNotEqualByComparingTo("67500")
			.isNotEqualByComparingTo("67000");
		assertThat(response.sellVsHighRate()).isEqualTo(new BigDecimal("-0.0325"));
		assertThat(response.sellVsLowRate()).isEqualTo(new BigDecimal("0.0059"));
		// 파생 사실이 채워지는 전제 — 같은 원본 거래일 안에서 완결된 매매다.
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

	// --- 장 마감 게이트 ⑬·⑭ (완료 조건 13·14번) ---

	// 15:30 분봉이 없는 날로 만든다 — 리터럴 15:30으로 마지막 분봉을 찾는 구현이면 closePrice·atClose가 예외도
	// 없이 빈다(§C-2-1).
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
		// 보유 구간 극값은 게이트와 무관하게 나간다 — 매도 시각까지는 이미 재생이 끝난 구간이다.
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
		// (69,200 − 68,500) ÷ 68,500 = 0.0102. 극값 비율과 기준가 자리가 뒤바뀐다.
		assertThat(response.postSellFlow().sellToCloseRate()).isEqualTo(new BigDecimal("0.0102"));
		assertThat(response.postSellFlow().postSellHighPrice()).isEqualByComparingTo("69500");
		assertThat(response.postSellFlow().postSellHighAt())
			.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 5)));
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().atClose().at())
			.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LAST_CANDLE_TIME));
		// 이슈 #212 1번 — buyBasis 700,105(=700,000+105), quantity 10. (69,200×10 − FLOOR(692,000×0.00015)) −
		// 700,105 = 691,897 − 700,105 = −8,208 → −8,208÷700,105 → −0.0117(api-contracts.md 예시와 같다).
		assertThat(response.counterfactuals().atClose().returnRate()).isEqualTo(new BigDecimal("-0.0117"));
		// 이 픽스처는 카드를 저장하지 않는다(saveFullDayCandles는 분봉만 심는다) — 보유 구간에 카드가 0건이라
		// 4번 항목의 판정 순서상 NO_EVENT가 1순위다(§C-4). NOT_YET은 카드는 있는데 확정 집계 행이 없을 때다.
		assertThat(response.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NO_EVENT);
		assertThat(response.peerComparison().priceMoveId()).isNull();
	}

	// 게이트 ⑭ — "오늘 15:30"으로 잡은 구현이면 다음 날 10:00은 아직 마감 전이라 어제 READY였던 값이 NOT_YET으로
	// 되돌아간다. 같은 날 조회만 재현하면 두 구현이 같은 답을 낸다.
	@Test
	@DisplayName("전날 매도 건을 다음 날 오전 10:00에 조회해도 READY를 유지한다")
	void keepsPostSellFlowReadyForAYesterdayTradeViewedTheNextMorning() {
		saveFullDayCandles();
		mutableClock.set(NEXT_DAY_VIEW);

		PostSellFeedbackResponse response = getPostSellFeedback();

		// 픽스처 자기검증 — 조회 시각이 그날 15:30 이전이고 날짜는 하루 넘었다.
		assertThat(NEXT_DAY_VIEW.toLocalTime()).isBefore(LocalTime.of(15, 30));
		assertThat(NEXT_DAY_VIEW.toLocalDate()).isAfter(TRADE_SERVICE_DATE);

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("69200");
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
	}

	// 게이트가 열렸는데 분봉이 없는 경우 — status는 게이트만 반영하므로 READY에 값만 null이다. 바로 위
	// 테스트(분봉이 있는 경우)와 짝이며, 한쪽만 재현하면 status를 데이터 유무로 판정한 구현이 걸리지 않는다.
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

	// --- 픽스처 ---

	/** 매도(11:30) 전후를 함께 담은 하루치 분봉. <b>마지막 분봉이 15:27이고 15:30 분봉은 없다.</b> */
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

	// 보유 구간 안에서 끝나고 근거 기사도 구간 안에 있어 일찍 열리는 카드.
	private PriceMoveEvent saveRevealedCard() {
		PriceMoveEvent card = saveCard(
			PriceMoveEventType.INTRADAY, LocalTime.of(10, 20), LocalTime.of(10, 25), LocalTime.of(10, 26));
		saveSource(card, saveNews("10:15 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 15))));
		return card;
	}

	// 보유 구간 안에서 끝났지만 근거 기사가 11:50에 발행돼 revealTime이 11:55로 밀린 카드. 근거 둘 중 하나가
	// 09:00 기사라 게이트가 새면 buyToNewsMinutes로 그 시각이 드러난다.
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
