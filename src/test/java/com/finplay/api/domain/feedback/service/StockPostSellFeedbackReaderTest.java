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

class StockPostSellFeedbackReaderTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long SELL_TRADE_ID = 2L;
	private static final Long INSTRUMENT_ID = 7L;

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

	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

	private final StockPostSellFeedbackReader stockPostSellFeedbackReader = new StockPostSellFeedbackReader(
		stockReplayService,
		priceMoveEventRepository,
		new PriceMoveSourceLoader(priceMoveEventSourceRepository),
		priceMovePeerStatRepository,
		Clock.fixed(SELL_SERVICE_DATE.atTime(SELL_TIME).atZone(KST).toInstant(), KST));


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
		assertThat(response.returnRate()).isEqualTo(new BigDecimal("-0.0217"));
	}

	@Test
	@DisplayName("returnRate 분모에 배분된 매수수수료가 들어간다")
	void returnRateDenominatorIncludesAllocatedBuyFee() {
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
		assertThat(response.returnRate()).isNotEqualTo(new BigDecimal("-0.1000"));
	}


	@Test
	@DisplayName("buyAt은 배분된 두 lot 중 가장 이른 시각이고 날짜는 원본 거래일이다")
	void buyAtIsTheEarliestAllocatedLotOnTheOriginTradeDateAxis() {
		SellAllocationSummaryDto allocation = twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE);

		PostSellFeedbackResponse response = read(sellTrade(ORIGIN_TRADE_DATE), allocation);

		assertThat(allocation.buySourceTradingDates()).hasSize(2);
		assertThat(LATER_BUY_TIME).isNotEqualTo(EARLIEST_BUY_TIME);

		assertThat(response.buyAt()).isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, EARLIEST_BUY_TIME));
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
		assertThat(response.holdingMinutes()).isNotEqualTo(250);
	}

	@Test
	@DisplayName("매도의 원본 거래일이 매수 lot보다 앞서면 holdingMinutes가 null이다 — 음수를 내지 않는다")
	void holdingMinutesIsNullWhenTheOriginTradeDatesAreReversed() {
		LocalDate laterOriginTradeDate = ORIGIN_TRADE_DATE.plusDays(1);

		PostSellFeedbackResponse response = read(
			sellTrade(ORIGIN_TRADE_DATE), twoLotSummary(laterOriginTradeDate, laterOriginTradeDate));

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

		assertThat(response.sameSessionCompleted()).isFalse();
		assertThat(response.buyAt()).isEqualTo(LocalDateTime.of(earlierOriginTradeDate, EARLIEST_BUY_TIME));
		assertThat(response.holdingMinutes()).isEqualTo(1750);
	}


	@Test
	@DisplayName("배분 lot이 전부 매도와 같은 원본 거래일이면 sameSessionCompleted=true다")
	void sameSessionCompletedIsTrueWhenEveryAllocatedLotSharesTheSellOriginTradeDate() {
		PostSellFeedbackResponse response = read(
			sellTrade(ORIGIN_TRADE_DATE), twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));

		assertThat(response.sameSessionCompleted()).isTrue();
	}

	@Test
	@DisplayName("나중 lot만 원본 거래일이 다르면 sameSessionCompleted=false다 — 가장 이른 lot만 보는 구현은 여기서 true를 낸다")
	void sameSessionCompletedIsFalseWhenOnlyALaterLotHasADifferentOriginTradeDate() {
		LocalDate otherOriginTradeDate = LocalDate.of(2026, 7, 30);
		SellAllocationSummaryDto allocation = twoLotSummary(ORIGIN_TRADE_DATE, otherOriginTradeDate);

		PostSellFeedbackResponse response = read(sellTrade(ORIGIN_TRADE_DATE), allocation);

		boolean earliestLotOnlyVerdict = ORIGIN_TRADE_DATE.equals(allocation.earliestBuySourceTradingDate());
		assertThat(earliestLotOnlyVerdict).isTrue();
		assertThat(allocation.buySourceTradingDates()).contains(otherOriginTradeDate);

		assertThat(response.sameSessionCompleted()).isFalse();
	}

	@Test
	@DisplayName("원본 거래일이 같아도 시각이 역전되면 sameSessionCompleted=false이고 파생 사실이 전부 빈다")
	void sameSessionCompletedIsFalseWhenTheTimesAreReversedWithinTheSameOriginTradeDate() {
		SellAllocationSummaryDto allocation = reversedSameDateSummary();

		PostSellFeedbackResponse response = read(sellTrade(ORIGIN_TRADE_DATE), allocation);

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
		verifyNoInteractions(stockReplayService, priceMoveEventRepository, priceMoveEventSourceRepository);
	}


	@Test
	@DisplayName("AI 서술 셋은 계약의 필드 집합을 유지한 채 null이다 — 서술은 이 트랜잭션 밖에서 얹는다")
	void leavesTheNarrativeTripleNull() {
		PostSellFeedbackResponse response = read(
			sellTrade(ORIGIN_TRADE_DATE), twoLotSummary(ORIGIN_TRADE_DATE, ORIGIN_TRADE_DATE));

		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(response.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NO_EVENT);
		assertThat(response.narrative()).isNull();
		assertThat(response.narrativeSource()).isNull();
		assertThat(response.narrativeStatus()).isNull();
	}


	private PostSellFeedbackResponse read(Trade trade, SellAllocationSummaryDto allocation) {
		return stockPostSellFeedbackReader.read(trade, allocation);
	}

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
