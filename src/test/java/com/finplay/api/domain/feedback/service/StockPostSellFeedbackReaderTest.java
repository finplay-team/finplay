// 주식 매도 회고 조립의 원장 수치·buyAt 선정·holdingMinutes·sameSessionCompleted 판정을 검증하는 단위 테스트다.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// tasks-282.md 1번 항목이 PostSellFeedbackReaderTest에서 이리로 옮긴 단정들이다 — 정본은
// docs/api/feedback.md의 "매도 직후 피드백 조회" 소절과 spec 012 FEED-007이고, 이슈 #208 1번 항목이 소유한
// 완료 조건 중 여러 lot의 buyAt과 서로 다른 원본 거래일이면 sameSessionCompleted=false를 본다.
//
// **검증(404·403·400)과 배분 조회는 이 클래스의 책임이 아니다** — PostSellFeedbackContextReaderTest가 보고,
// 시장 분기·위임은 PostSellFeedbackReaderTest가 본다. 여기서는 이미 검증을 마친 (trade, allocation)을 그대로
// 넘겨 조립 결과만 본다(CryptoPostSellFeedbackReaderTest와 같은 모양이다).
//
// 픽스처의 수치는 계약 예시 그대로다(매수원가 700,000 + 매수수수료 105, 매도 685,000 − 수수료 102,
// realizedPnl −15,207). 계약이 "값이 안 맞으면 예시가 아니라 구현이 틀린 것"이라고 적어 둔 자리다.
class StockPostSellFeedbackReaderTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long SELL_TRADE_ID = 2L;
	private static final Long INSTRUMENT_ID = 7L;

	// 원본 거래일과 서비스 날짜를 다르게 둔다 — buyAt·sellAt을 trades.executed_at(서비스 벽시계) 그대로 쓴
	// 구현이면 날짜가 어긋나 여기서 빨개진다(계약 — "원본 거래일 기준 체결 시각").
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate SELL_SERVICE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate EARLIEST_BUY_SERVICE_DATE = LocalDate.of(2026, 8, 3);

	private static final LocalTime EARLIEST_BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime LATER_BUY_TIME = LocalTime.of(10, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository = mock(
		PriceMoveEventSourceRepository.class);

	// peerComparison 상태 판정은 PostSellFeedbackPeerComparisonTest가 전담한다 — 이 파일은 원장 수치·buyAt·
	// sameSessionCompleted만 보므로 stub하지 않고 Mockito 기본값(Optional.empty(), 곧 NOT_YET)으로 둔다.
	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

	// 파생 사실이 카드 노출 게이트에 그 체결의 서비스 날짜를 쓰므로 시계가 필요하다. 이 파일이 보는 완료 조건은
	// 게이트가 아니라 수치·buyAt·sameSessionCompleted라, 매도 서비스 날짜의 장중 시각으로 고정해 게이트가
	// 판정을 가리지 않게 둔다 — 게이트 자체(⑮)는 통합 테스트가 고정 Clock으로 본다.
	private final StockPostSellFeedbackReader stockPostSellFeedbackReader = new StockPostSellFeedbackReader(
		stockReplayService,
		priceMoveEventRepository,
		new PriceMoveSourceLoader(priceMoveEventSourceRepository),
		priceMovePeerStatRepository,
		Clock.fixed(SELL_SERVICE_DATE.atTime(SELL_TIME).atZone(KST).toInstant(), KST));

	// --- 원장 수치 ---

	@Test
	@DisplayName("원장 수치를 계약 예시 그대로 돌려준다 — returnRate는 scale 4 HALF_UP이다")
	void returnsLedgerNumbersExactlyAsTheContractExample() {
		PostSellFeedbackResponse response = read(
			sellTrade(ORIGIN_TRADE_DATE), twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));

		assertThat(response.tradeId()).isEqualTo(SELL_TRADE_ID);
		assertThat(response.instrumentId()).isEqualTo(INSTRUMENT_ID);
		assertThat(response.symbol()).isEqualTo("005930");
		assertThat(response.name()).isEqualTo("삼성전자");
		assertThat(response.buyPrice()).isEqualByComparingTo("70000");
		assertThat(response.sellPrice()).isEqualByComparingTo("68500");
		assertThat(response.quantity()).isEqualByComparingTo("10");
		assertThat(response.fee()).isEqualTo(102L);
		assertThat(response.realizedPnl()).isEqualTo(-15_207L);
		// −15,207 ÷ (700,000 + 105) = −0.02172102… → scale 4 HALF_UP. scale까지 고정한다.
		assertThat(response.returnRate()).isEqualTo(new BigDecimal("-0.0217"));
	}

	// 배분 원가 합만 쓰고 매수수수료를 분모에서 빼먹으면 −15,207 ÷ 700,000 = −0.0217(같은 값)이 되어 이 단정만
	// 으로는 안 잡힌다. 그래서 수수료가 분모에 실제로 들어가는지를 값이 갈리는 픽스처로 따로 본다.
	@Test
	@DisplayName("returnRate 분모에 배분된 매수수수료가 들어간다")
	void returnRateDenominatorIncludesAllocatedBuyFee() {
		// 원가 100,000 + 수수료 10,000 = 110,000. 수수료를 빼먹으면 −0.1000이 되고 포함하면 −0.0909다.
		SellAllocationSummaryDto allocation = new SellAllocationSummaryDto(
			new BigDecimal("10000.00000000"),
			LocalDateTime.of(EARLIEST_BUY_SERVICE_DATE, EARLIEST_BUY_TIME),
			ORIGIN_TRADE_DATE,
			100_000L,
			10_000L,
			new BigDecimal("10"),
			List.of(ORIGIN_TRADE_DATE));

		PostSellFeedbackResponse response = read(sellTrade(ORIGIN_TRADE_DATE, -10_000L), allocation);

		assertThat(response.returnRate()).isEqualTo(new BigDecimal("-0.0909"));
		// 수수료를 분모에서 빼먹은 구현이 내는 답 — 두 답이 갈리는 픽스처임을 남긴다.
		assertThat(response.returnRate()).isNotEqualTo(new BigDecimal("-0.1000"));
	}

	// --- buyAt·sellAt·holdingMinutes ---

	@Test
	@DisplayName("buyAt은 배분된 두 lot 중 가장 이른 시각이고 날짜는 원본 거래일이다")
	void buyAtIsTheEarliestAllocatedLotOnTheOriginTradeDateAxis() {
		SellAllocationSummaryDto allocation = twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE);

		PostSellFeedbackResponse response = read(sellTrade(ORIGIN_TRADE_DATE), allocation);

		// 픽스처 자기검증 — lot이 둘이고 시각이 다르므로 "나중 lot을 쓴 구현"은 10:30이 되어 이 단정에서 빨개진다.
		assertThat(allocation.buySourceTradingDates()).hasSize(2);
		assertThat(LATER_BUY_TIME).isNotEqualTo(EARLIEST_BUY_TIME);

		assertThat(response.buyAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, EARLIEST_BUY_TIME));
		// 서비스 벽시계를 그대로 쓴 구현이 내는 답 — 실제 응답이 그것과 달라야 한다.
		assertThat(response.buyAt()).isNotEqualTo(allocation.earliestBuyAt());
		assertThat(response.sellAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, SELL_TIME));
		assertThat(response.sellAt()).isNotEqualTo(LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
	}

	@Test
	@DisplayName("holdingMinutes는 응답의 buyAt~sellAt 사이다 — 09:30~14:40이면 310분이다")
	void holdingMinutesSpansTheTwoTimestampsInTheResponse() {
		PostSellFeedbackResponse response = read(
			sellTrade(ORIGIN_TRADE_DATE), twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));

		assertThat(response.holdingMinutes()).isEqualTo(310);
		// 나중 lot(10:30)을 기준으로 잰 구현이 내는 답 — 두 답이 갈리는 픽스처임을 남긴다.
		assertThat(response.holdingMinutes()).isNotEqualTo(250);
	}

	// 2026-08-04 결정 — 원본 거래일이 역전되면 holdingMinutes가 null이다(§파생 사실 계산). 같은 원본 거래일을
	// 여러 서비스 날짜에 재생할 수 있어 매도의 원본 거래일이 매수 lot보다 앞선 조합이 실제로 성립하고, 그때 음수가
	// 예외도 로그도 없이 나갔다. 아래 두 테스트는 짝이다 — 역전만 재현하면 "sameSessionCompleted=false면 전부
	// null"로 잘못 구현한 코드도 초록이기 때문이다.
	@Test
	@DisplayName("매도의 원본 거래일이 매수 lot보다 앞서면 holdingMinutes가 null이다 — 음수를 내지 않는다")
	void holdingMinutesIsNullWhenTheOriginTradeDatesAreReversed() {
		LocalDate laterOriginTradeDate = ORIGIN_TRADE_DATE.plusDays(1);

		PostSellFeedbackResponse response = read(
			sellTrade(ORIGIN_TRADE_DATE), twoLotSummary(laterOriginTradeDate, laterOriginTradeDate));

		// 픽스처 자기검증 — 응답의 두 시각이 실제로 역전돼 있어야 이 규칙을 검증한다. 그대로 뺀 구현이 내는
		// 답이 음수이므로 두 구현이 갈린다(0으로 clamp한 구현도 이 단정에서 빨개진다).
		assertThat(response.sellAt()).isBefore(response.buyAt());
		assertThat(Duration.between(response.buyAt(), response.sellAt()).toMinutes()).isNegative();
		assertThat(response.holdingMinutes()).isNull();
	}

	@Test
	@DisplayName("원본 거래일이 순방향이면 sameSessionCompleted=false여도 holdingMinutes는 채워진다")
	void holdingMinutesStaysFilledForAForwardCrossSessionSell() {
		LocalDate earlierOriginTradeDate = ORIGIN_TRADE_DATE.minusDays(1);

		PostSellFeedbackResponse response = read(
			sellTrade(ORIGIN_TRADE_DATE), twoLotSummary(earlierOriginTradeDate, earlierOriginTradeDate));

		// 계약의 sameSessionCompleted=false nullable 목록에 holdingMinutes는 없다 — 조건은 역전뿐이다.
		assertThat(response.sameSessionCompleted()).isFalse();
		assertThat(response.buyAt()).isEqualTo(LocalDateTime.of(earlierOriginTradeDate, EARLIEST_BUY_TIME));
		assertThat(response.holdingMinutes()).isEqualTo(1750);
	}

	// --- sameSessionCompleted ---

	@Test
	@DisplayName("배분 lot이 전부 매도와 같은 원본 거래일이면 sameSessionCompleted=true다")
	void sameSessionCompletedIsTrueWhenEveryAllocatedLotSharesTheSellOriginTradeDate() {
		PostSellFeedbackResponse response = read(
			sellTrade(ORIGIN_TRADE_DATE), twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));

		assertThat(response.sameSessionCompleted()).isTrue();
	}

	// 픽스처가 "가장 이른 lot만 보는 구현"에서 실제로 빨간지가 이 테스트의 핵심이다 — 가장 이른 lot의 원본
	// 거래일을 매도와 같게 두고, 나중 lot만 다른 거래일로 둔다. 두 lot이 같은 거래일인 픽스처로는 두 구현이 같은
	// 답을 내므로 회귀를 못 잡는다.
	@Test
	@DisplayName("나중 lot만 원본 거래일이 다르면 sameSessionCompleted=false다 — 가장 이른 lot만 보는 구현은 여기서 true를 낸다")
	void sameSessionCompletedIsFalseWhenOnlyALaterLotHasADifferentOriginTradeDate() {
		LocalDate otherOriginTradeDate = LocalDate.of(2026, 7, 30);
		SellAllocationSummaryDto allocation = twoLotSummary(ORIGIN_TRADE_DATE, otherOriginTradeDate);

		PostSellFeedbackResponse response = read(sellTrade(ORIGIN_TRADE_DATE), allocation);

		// 틀린 구현이 내는 답을 테스트 안에서 재현한다 — 가장 이른 lot만 대조하면 true다.
		boolean earliestLotOnlyVerdict = ORIGIN_TRADE_DATE.equals(allocation.earliestBuySourceTradingDate());
		assertThat(earliestLotOnlyVerdict).isTrue();
		assertThat(allocation.buySourceTradingDates()).contains(otherOriginTradeDate);

		// 올바른 구현은 배분된 lot 전부를 보므로 false다.
		assertThat(response.sameSessionCompleted()).isFalse();
	}

	// 2026-08-05 결정 — 같은 원본 거래일을 두 서비스 날짜에 재생하면 첫 재생일 오후에 매수하고 다음 재생일
	// 오전에 매도할 수 있어 **원본 거래일은 같은 채로 시각만 역전되는** 조합이 성립한다. 날짜만 대조하는 구현은
	// true를 내는데 보유 구간이 빈 구간이라 파생 사실이 전부 null로 나가 계약의 "true면 채워진다"와 어긋난다.
	@Test
	@DisplayName("원본 거래일이 같아도 시각이 역전되면 sameSessionCompleted=false이고 파생 사실이 전부 빈다")
	void sameSessionCompletedIsFalseWhenTheTimesAreReversedWithinTheSameOriginTradeDate() {
		SellAllocationSummaryDto allocation = reversedSameDateSummary();

		PostSellFeedbackResponse response = read(sellTrade(ORIGIN_TRADE_DATE), allocation);

		// 틀린 구현이 내는 답을 재현한다 — lot 원본 거래일이 전부 매도와 같아 날짜 대조만으로는 true다.
		boolean dateOnlyVerdict = allocation.buySourceTradingDates().stream().allMatch(ORIGIN_TRADE_DATE::equals);
		assertThat(dateOnlyVerdict).isTrue();
		assertThat(response.sellAt()).isBefore(response.buyAt());

		assertThat(response.sameSessionCompleted()).isFalse();
		assertThat(response.holdingMinutes()).isNull();
		assertThat(response.holdHighPrice()).isNull();
		assertThat(response.holdHighAt()).isNull();
		assertThat(response.holdLowPrice()).isNull();
		assertThat(response.holdLowAt()).isNull();
		assertThat(response.sellVsHighRate()).isNull();
		assertThat(response.sellVsLowRate()).isNull();
		assertThat(response.buyToNewsMinutes()).isNull();
		assertThat(response.priceMoves()).isEmpty();
		assertThat(response.postSellFlow()).isNull();
		assertThat(response.counterfactuals()).isNull();
		assertThat(response.peerComparison()).isNull();
		// 계산이 성립하지 않는 조합이라 분봉·카드를 읽지도 않는다.
		verifyNoInteractions(stockReplayService, priceMoveEventRepository, priceMoveEventSourceRepository);
	}

	// --- 이 클래스가 채우지 않는 필드 ---

	@Test
	@DisplayName("AI 서술 셋은 계약의 필드 집합을 유지한 채 null이다 — 서술은 이 트랜잭션 밖에서 얹는다")
	void leavesTheNarrativeTripleNull() {
		PostSellFeedbackResponse response = read(
			sellTrade(ORIGIN_TRADE_DATE), twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));

		// 매도 후 흐름·반사실은 이 픽스처가 장 마감 전(14:40) 조회라 게이트가 닫혀 있어 NOT_YET 껍데기다.
		// 게이트 자체는 PostSellFeedbackPostSellFlowTest가 본다. 집단 비교는 확정 집계 행 존재가 아니라 카드
		// 0건으로 NO_EVENT가 1순위 판정된다(§C-4) — 상태값 4가지 분기는 PostSellFeedbackPeerComparisonTest가 본다.
		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(response.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NO_EVENT);
		// 서술 셋은 PostSellFeedbackService가 withNarrative로 얹는다.
		assertThat(response.narrative()).isNull();
		assertThat(response.narrativeSource()).isNull();
		assertThat(response.narrativeStatus()).isNull();
	}

	// --- 픽스처 ---

	private PostSellFeedbackResponse read(Trade trade, SellAllocationSummaryDto allocation) {
		return stockPostSellFeedbackReader.read(trade, allocation);
	}

	/**
	 * 계약 예시의 배분 요약. lot이 <b>둘</b>이고 가장 이른 lot은 09:30, 나중 lot은 10:30이다. 가장 이른 lot의
	 * 벽시계 날짜를 매도의 서비스 날짜와 다르게 둬서, 그 값을 그대로 buyAt으로 쓴 구현이 드러나게 한다.
	 */
	private static SellAllocationSummaryDto twoLotSummary(
		LocalDate earliestLotOriginTradeDate, LocalDate laterLotOriginTradeDate) {
		return new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"),
			LocalDateTime.of(EARLIEST_BUY_SERVICE_DATE, EARLIEST_BUY_TIME),
			earliestLotOriginTradeDate,
			700_000L,
			105L,
			new BigDecimal("10"),
			List.of(earliestLotOriginTradeDate, laterLotOriginTradeDate));
	}

	/**
	 * 원본 거래일은 매도와 같지만 매수 시각이 매도보다 <b>늦은</b> 배분 요약 — 같은 원본 거래일을 두 서비스
	 * 날짜에 재생했을 때 성립하는 조합이다(첫 재생일 15:10 매수 → 다음 재생일 14:40 매도).
	 */
	private static SellAllocationSummaryDto reversedSameDateSummary() {
		return new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"),
			LocalDateTime.of(EARLIEST_BUY_SERVICE_DATE, LocalTime.of(15, 10)),
			ORIGIN_TRADE_DATE,
			700_000L,
			105L,
			new BigDecimal("10"),
			List.of(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));
	}

	private static Trade sellTrade(LocalDate originTradeDate) {
		return sellTrade(originTradeDate, -15_207L);
	}

	private static Trade sellTrade(LocalDate originTradeDate, Long realizedPnl) {
		Instrument instrument = stockInstrument();
		LocalDateTime executedAt = LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME);
		LocalDateTime resolvedAt = LocalDateTime.of(SELL_SERVICE_DATE, LocalTime.of(8, 40));
		StockReplaySession session = StockReplaySession.ready(
			SELL_SERVICE_DATE, originTradeDate, resolvedAt, resolvedAt);
		User user = User.create("trader@finplay.com", "password-hash", "trader", executedAt);
		Account account = Account.create(user, Market.STOCK, executedAt);
		Order order = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET, new BigDecimal("10"), "idem-key",
			"h".repeat(64), executedAt);
		Trade trade = Trade.of(
			order, account, instrument, session, OrderSide.SELL, new BigDecimal("68500"), new BigDecimal("10"),
			685_000L, 102L, realizedPnl, executedAt, executedAt);
		ReflectionTestUtils.setField(trade, "id", SELL_TRADE_ID);
		return trade;
	}

	private static Instrument stockInstrument() {
		Instrument instrument = Instrument.create(
			Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true,
			LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME));
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		return instrument;
	}
}
