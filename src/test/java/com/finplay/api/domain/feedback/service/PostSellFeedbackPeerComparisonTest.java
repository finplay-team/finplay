// peerComparison 상태 판정(NO_EVENT·NOT_YET·INSUFFICIENT_SAMPLE·READY)과 지표 계산을 검증하는 단위 테스트다.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.entity.PriceMovePeerStat;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
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
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * tasks.md 012 이슈 #212 4번 항목 — 정본은 spec.md §C-4·§반사실·집단 비교 계산이다.
 *
 * <p>판정 순서는 {@code NO_EVENT}(카드 0건) → {@code NOT_YET}(카드는 있지만 확정 집계 행 없음) →
 * {@code INSUFFICIENT_SAMPLE}({@code holderCount < 5}) → {@code READY}다. 경계(5명/4명)는 두 픽스처를 모두 두어
 * {@code <}/{@code <=} 뒤바뀜을 잡는다.
 */
class PostSellFeedbackPeerComparisonTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long SELL_TRADE_ID = 2L;
	private static final Long INSTRUMENT_ID = 7L;
	private static final Long CARD_ID = 12L;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate SELL_SERVICE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime CARD_WINDOW_START = LocalTime.of(9, 45);
	private static final LocalTime CARD_WINDOW_END = LocalTime.of(9, 50);
	// yourMinutesToSell = 14:40 − 09:50 = 290분. 계산과 무관한 다른 픽스처의 값들과 겹치지 않는 값으로 고른다.
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository = mock(
		PriceMoveEventSourceRepository.class);

	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

	// 검증(404·403·400)과 배분 조회는 PostSellFeedbackContextReader로 옮겨 갔다(이슈 #282) — 이 파일은 이미
	// 검증을 마친 (trade, allocation)을 주식 조립에 그대로 넘겨 집단 비교만 본다.
	private final StockPostSellFeedbackReader stockPostSellFeedbackReader = new StockPostSellFeedbackReader(
		stockReplayService,
		priceMoveEventRepository,
		new PriceMoveSourceLoader(priceMoveEventSourceRepository),
		priceMovePeerStatRepository,
		Clock.fixed(SELL_SERVICE_DATE.atTime(SELL_TIME).atZone(KST).toInstant(), KST));

	private Trade trade;

	private SellAllocationSummaryDto allocation;

	// --- 판정 순서 1: NO_EVENT (1순위) ---

	@Test
	@DisplayName("보유 구간에 카드가 0건이면 NO_EVENT이고 priceMoveId를 포함한 전 필드가 null이다")
	void statusIsNoEventWithoutAnyFieldWhenNoCardExistsInTheHeldWindow() {
		givenSameSessionSell();
		givenNoCards();

		PeerComparison peerComparison = read().peerComparison();

		assertThat(peerComparison.status()).isEqualTo(PostSellFeedbackStatus.NO_EVENT);
		assertThat(peerComparison.priceMoveId()).isNull();
		assertThat(peerComparison.holderCount()).isNull();
		assertThat(peerComparison.soldWithin30MinRate()).isNull();
		assertThat(peerComparison.medianMinutesToSell()).isNull();
		// yourMinutesToSell도 null이다 — 기준 카드 자체가 없어 잴 대상이 없다.
		assertThat(peerComparison.yourMinutesToSell()).isNull();
	}

	// --- 판정 순서 2: NOT_YET (카드는 있지만 확정 집계 행이 없다) ---

	@Test
	@DisplayName("카드는 있지만 확정 집계 행이 없으면 NOT_YET이고 priceMoveId조차 채우지 않는다")
	void statusIsNotYetWhenTheCardExistsButNoConfirmedStatRowYet() {
		givenSameSessionSell();
		givenCard(card());
		// 리포지토리를 stub하지 않으면 Mockito 기본값 Optional.empty()다 — 확정 집계 행이 없는 상태를 그대로 뜻한다.

		PeerComparison peerComparison = read().peerComparison();

		assertThat(peerComparison.status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		// 기준 카드를 이미 알고 있어도(반사실 atFirstMoveAfterBuy와 같은 카드) priceMoveId를 채우지 않는다 — 배치가
		// 돌아 판정이 바뀌는 순간 값 → null로 사라지는 조합이 생기지 않게 한다.
		assertThat(peerComparison.priceMoveId()).isNull();
		assertThat(peerComparison.yourMinutesToSell()).isNull();
	}

	// --- 함정: INSUFFICIENT_SAMPLE 경계 (holderCount 4 vs 5) ---

	@Test
	@DisplayName("holderCount=4(경계 미달)면 INSUFFICIENT_SAMPLE이고 모집단 지표 3종이 null, yourMinutesToSell만 채운다")
	void statusIsInsufficientSampleWhenHolderCountIsFour() {
		givenSameSessionSell();
		givenCard(card());
		givenConfirmedStat(PriceMovePeerStat.create(
			card(), SELL_SERVICE_DATE, 4, 1, 15, LocalDateTime.of(SELL_SERVICE_DATE, LocalTime.of(15, 40))));

		PeerComparison peerComparison = read().peerComparison();

		assertThat(peerComparison.status()).isEqualTo(PostSellFeedbackStatus.INSUFFICIENT_SAMPLE);
		assertThat(peerComparison.priceMoveId()).isEqualTo(CARD_ID);
		assertThat(peerComparison.holderCount()).isNull();
		assertThat(peerComparison.soldWithin30MinRate()).isNull();
		assertThat(peerComparison.medianMinutesToSell()).isNull();
		// yourMinutesToSell은 모집단 통계가 아니라 본인 값이라 표본 부족이어도 채운다.
		assertThat(peerComparison.yourMinutesToSell()).isEqualTo(290);
	}

	@Test
	@DisplayName("holderCount=5(경계 포함)면 READY다 — 4명 픽스처만으로는 </<=를 뒤바꾼 구현을 못 잡는다")
	void statusIsReadyWhenHolderCountIsExactlyFive() {
		givenSameSessionSell();
		givenCard(card());
		givenConfirmedStat(PriceMovePeerStat.create(
			card(), SELL_SERVICE_DATE, 5, 1, 20, LocalDateTime.of(SELL_SERVICE_DATE, LocalTime.of(15, 40))));

		PeerComparison peerComparison = read().peerComparison();

		assertThat(peerComparison.status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(peerComparison.holderCount()).isEqualTo(5);
		assertThat(peerComparison.medianMinutesToSell()).isEqualTo(20);
		assertThat(peerComparison.yourMinutesToSell()).isEqualTo(290);
	}

	// --- soldWithin30MinRate = soldWithin30MinCount ÷ holderCount ---

	@Test
	@DisplayName("soldWithin30MinRate는 나누어지지 않는 값(2/7)에서도 scale 4 HALF_UP으로 정확히 나온다")
	void computesSoldWithin30MinRateAsAnExactDivisionNotAnIntegerTruncation() {
		givenSameSessionSell();
		givenCard(card());
		// 2 ÷ 7 = 0.285714285714... → scale 4 HALF_UP = 0.2857. 정수 나눗셈(2/7=0)으로 잘못 구현하면 0이 나온다.
		givenConfirmedStat(PriceMovePeerStat.create(
			card(), SELL_SERVICE_DATE, 7, 2, 12, LocalDateTime.of(SELL_SERVICE_DATE, LocalTime.of(15, 40))));

		PeerComparison peerComparison = read().peerComparison();

		assertThat(peerComparison.status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(peerComparison.soldWithin30MinRate()).isEqualTo(new BigDecimal("0.2857"));
		// 정수 나눗셈 구현이 내는 답 — 두 답이 갈리는 픽스처임을 남긴다.
		assertThat(peerComparison.soldWithin30MinRate()).isNotEqualTo(BigDecimal.ZERO);
	}

	// --- yourMinutesToSell = 매도시각 − 카드 windowEnd ---

	@Test
	@DisplayName("yourMinutesToSell은 매도시각 − 카드 windowEnd(분)와 정확히 같다")
	void yourMinutesToSellMatchesSellAtMinusCardWindowEnd() {
		givenSameSessionSell();
		givenCard(card());
		givenConfirmedStat(PriceMovePeerStat.create(
			card(), SELL_SERVICE_DATE, 5, 1, 20, LocalDateTime.of(SELL_SERVICE_DATE, LocalTime.of(15, 40))));

		PeerComparison peerComparison = read().peerComparison();

		// 매도 14:40 − windowEnd 09:50 = 290분.
		assertThat(peerComparison.yourMinutesToSell()).isEqualTo(290);
	}

	// --- 회원 식별자 부재 ---

	@Test
	@DisplayName("PeerComparison 응답 필드 어디에도 회원 식별자가 없다")
	void peerComparisonHasNoMemberIdentifierField() {
		List<String> fieldNames = Arrays.stream(PeerComparison.class.getRecordComponents())
			.map(RecordComponent::getName)
			.toList();

		assertThat(fieldNames).noneMatch(name -> name.toLowerCase().contains("user")
			|| name.toLowerCase().contains("member")
			|| name.toLowerCase().contains("account")
			|| name.toLowerCase().contains("nickname"));
	}

	// --- 픽스처 ---

	private PostSellFeedbackResponse read() {
		return stockPostSellFeedbackReader.read(trade, allocation);
	}

	private void givenSameSessionSell() {
		trade = sellTrade();
		allocation = allocation();
	}

	private void givenNoCards() {
		when(priceMoveEventRepository
			.findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				any(), any(), any(), any(), any()))
			.thenReturn(List.of());
	}

	private void givenCard(PriceMoveEvent event) {
		when(priceMoveEventRepository
			.findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				any(), any(), any(), any(), any()))
			.thenReturn(List.of(event));
		when(priceMoveEventSourceRepository.findAllByPriceMoveEventIdIn(any())).thenReturn(List.of());
	}

	private void givenConfirmedStat(PriceMovePeerStat stat) {
		when(priceMovePeerStatRepository.findByPriceMoveEventIdAndServiceDate(CARD_ID, SELL_SERVICE_DATE))
			.thenReturn(Optional.of(stat));
	}

	private static PriceMoveEvent card() {
		PriceMoveEvent event = PriceMoveEvent.createStock(
			stockInstrument(),
			PriceMoveEventType.INTRADAY,
			ORIGIN_TRADE_DATE,
			CARD_WINDOW_START,
			CARD_WINDOW_END,
			new BigDecimal("0.050000"),
			new BigDecimal("3.0000"),
			"테스트 카드",
			NarrativeSource.LLM,
			CARD_WINDOW_END.plusMinutes(1),
			LocalDateTime.of(ORIGIN_TRADE_DATE, CARD_WINDOW_END));
		ReflectionTestUtils.setField(event, "id", CARD_ID);
		return event;
	}

	private static SellAllocationSummaryDto allocation() {
		return new SellAllocationSummaryDto(
			new BigDecimal("70000.00000000"),
			LocalDateTime.of(SELL_SERVICE_DATE, BUY_TIME),
			ORIGIN_TRADE_DATE,
			700_000L,
			105L,
			new BigDecimal("10"),
			List.of(ORIGIN_TRADE_DATE));
	}

	private static Trade sellTrade() {
		Instrument instrument = stockInstrument();
		LocalDateTime resolvedAt = LocalDateTime.of(SELL_SERVICE_DATE, LocalTime.of(8, 40));
		StockReplaySession session = StockReplaySession.ready(
			SELL_SERVICE_DATE, ORIGIN_TRADE_DATE, resolvedAt, resolvedAt);
		LocalDateTime executedAt = LocalDateTime.of(SELL_SERVICE_DATE, SELL_TIME);
		User user = User.create("trader@finplay.com", "password-hash", "trader", executedAt);
		Account account = Account.create(user, Market.STOCK, executedAt);
		Order order = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET, new BigDecimal("10"), "idem-key",
			"h".repeat(64), executedAt);
		Trade trade = Trade.of(
			order, account, instrument, session, OrderSide.SELL, new BigDecimal("68500"), new BigDecimal("10"),
			685_000L, 102L, -15_207L, executedAt, executedAt);
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
