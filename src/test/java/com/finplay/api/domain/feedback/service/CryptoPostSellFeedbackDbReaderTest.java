package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.entity.PriceMovePeerStat;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

class CryptoPostSellFeedbackDbReaderTest {

	private static final String SYMBOL = "BTC";
	private static final Long INSTRUMENT_ID = 7L;
	private static final Long FIRST_CARD_ID = 11L;
	private static final Long SECOND_CARD_ID = 12L;

	private static final LocalDate SELL_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDateTime BUY_AT = LocalDateTime.of(
		SELL_DATE.minusDays(1), LocalTime.of(23, 1, 17, 400_000_000));

	private static final LocalDateTime SELL_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(2, 20, 41, 100_000_000));

	private static final LocalDateTime FIRST_CARD_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(0, 30));
	private static final LocalDateTime SECOND_CARD_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(1, 40));

	private final FeedbackCryptoProperties cryptoProperties = new FeedbackCryptoProperties(30, 6, 5, 24, 100, 35, 45);

	private final PriceMoveEventRepository priceMoveEventRepository = mock(PriceMoveEventRepository.class);

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository = mock(
		PriceMoveEventSourceRepository.class);

	private final PriceMovePeerStatRepository priceMovePeerStatRepository = mock(PriceMovePeerStatRepository.class);

	private final CryptoPostSellFeedbackDbReader dbReader = new CryptoPostSellFeedbackDbReader(
		priceMoveEventRepository,
		new PriceMoveSourceLoader(priceMoveEventSourceRepository),
		priceMovePeerStatRepository,
		cryptoProperties);

	@Test
	@DisplayName("카드 조회 구간을 분으로 내려 넘기고 코인 축(instrumentId · CRYPTO · occurredAt)으로 찾는다")
	void floorsTheHoldWindowToTheMinuteWhenLookingUpCards() {
		givenCards();

		dbReader.findHeldPriceMoves(cryptoSellTrade(), BUY_AT, SELL_AT);

		ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
		ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
		verify(priceMoveEventRepository)
			.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
				eq(INSTRUMENT_ID), eq(Market.CRYPTO), from.capture(), to.capture());
		assertThat(from.getValue())
			.as("소수 초를 그대로 넘기면 매수 분과 같은 분에 탐지된 카드가 하한 밖으로 밀린다")
			.isEqualTo(LocalDateTime.of(SELL_DATE.minusDays(1), LocalTime.of(23, 1)));
		assertThat(to.getValue()).isEqualTo(LocalDateTime.of(SELL_DATE, LocalTime.of(2, 20)));
	}

	@Test
	@DisplayName("보유 구간에 카드가 없으면 빈 목록이고 근거 기사를 아예 조회하지 않는다")
	void returnsAnEmptyListWithoutLoadingSourcesWhenNoCardIsInTheHold() {
		givenCards();

		List<HeldPriceMoveItem> priceMoves = dbReader.findHeldPriceMoves(cryptoSellTrade(), BUY_AT, SELL_AT);

		assertThat(priceMoves).isEmpty();
		verifyNoInteractions(priceMoveEventSourceRepository);
	}

	@Test
	@DisplayName("windowStart를 occurredAt − rolling-window-minutes로 파생하고 두 간격을 분으로 채운다")
	void derivesTheWindowStartFromTheRollingWindowAndFillsBothGaps() {
		givenCards(card(FIRST_CARD_ID, FIRST_CARD_AT));
		givenNoSources();

		List<HeldPriceMoveItem> priceMoves = dbReader.findHeldPriceMoves(cryptoSellTrade(), BUY_AT, SELL_AT);

		assertThat(priceMoves).hasSize(1);
		HeldPriceMoveItem item = priceMoves.get(0);
		assertThat(item.id()).isEqualTo(FIRST_CARD_ID);
		assertThat(item.windowEnd()).isEqualTo(FIRST_CARD_AT);
		assertThat(item.windowStart()).isEqualTo(FIRST_CARD_AT.minusMinutes(5));
		assertThat(item.changeRate()).isEqualByComparingTo("0.021");
		assertThat(item.narrative()).isEqualTo("첫 카드");
		assertThat(item.minutesAfterBuy()).isEqualTo(89);
		assertThat(item.minutesBeforeSell()).isEqualTo(110);
		assertThat(item.sources()).isEmpty();
	}

	@Test
	@DisplayName("근거 기사가 카드별로 갈려 실리고 기사가 없는 카드는 빈 목록이다")
	void attachesSourcesPerCardAndLeavesCardsWithoutSourcesEmpty() {
		PriceMoveEvent first = card(FIRST_CARD_ID, FIRST_CARD_AT);
		givenCards(first, card(SECOND_CARD_ID, SECOND_CARD_AT));
		when(priceMoveEventSourceRepository.findAllByPriceMoveEventIdIn(anyList()))
			.thenReturn(List.of(sourceOf(first, "첫 카드 근거 기사")));

		List<HeldPriceMoveItem> priceMoves = dbReader.findHeldPriceMoves(cryptoSellTrade(), BUY_AT, SELL_AT);

		assertThat(priceMoves).extracting(HeldPriceMoveItem::id)
			.as("카드 순서는 리포지터리 정렬(occurredAt · id 오름차순)을 그대로 보존한다")
			.containsExactly(FIRST_CARD_ID, SECOND_CARD_ID);
		assertThat(priceMoves.get(0).sources()).extracting(NewsItem::title).containsExactly("첫 카드 근거 기사");
		assertThat(priceMoves.get(1).sources()).isEmpty();
	}

	@Test
	@DisplayName("카드가 0건이면 집계 행을 보지도 않고 NO_EVENT다")
	void returnsNoEventWithoutTouchingTheStatTableWhenThereIsNoCard() {
		PeerComparison peerComparison = dbReader.buildPeerComparison(List.of());

		assertThat(peerComparison.status()).isEqualTo(PostSellFeedbackStatus.NO_EVENT);
		assertThat(peerComparison.priceMoveId()).isNull();
		assertThat(peerComparison.yourMinutesToSell()).isNull();
		verifyNoInteractions(priceMovePeerStatRepository);
	}

	@Test
	@DisplayName("기준 카드는 첫 카드이고 조회 키가 그 카드 windowEnd의 KST 날짜다")
	void looksUpTheStatByTheFirstCardAndTheKstDateOfThatCard() {
		when(priceMovePeerStatRepository.findByPriceMoveEventIdAndServiceDate(any(), any()))
			.thenReturn(Optional.empty());

		dbReader.buildPeerComparison(List.of(heldCard(FIRST_CARD_ID, FIRST_CARD_AT), heldCard(SECOND_CARD_ID,
			SECOND_CARD_AT)));

		ArgumentCaptor<Long> cardId = ArgumentCaptor.forClass(Long.class);
		ArgumentCaptor<LocalDate> serviceDate = ArgumentCaptor.forClass(LocalDate.class);
		verify(priceMovePeerStatRepository)
			.findByPriceMoveEventIdAndServiceDate(cardId.capture(), serviceDate.capture());
		assertThat(cardId.getValue()).isEqualTo(FIRST_CARD_ID);
		assertThat(serviceDate.getValue())
			.as("매수일이나 체결의 서비스 날짜를 쓰면 행을 영원히 못 찾아 NOT_YET으로 굳는다")
			.isEqualTo(SELL_DATE)
			.isNotEqualTo(BUY_AT.toLocalDate());
	}

	@Test
	@DisplayName("확정 집계 행이 없으면 NOT_YET이고 priceMoveId조차 채우지 않는다")
	void returnsNotYetWithoutThePriceMoveIdWhenTheStatRowIsMissing() {
		when(priceMovePeerStatRepository.findByPriceMoveEventIdAndServiceDate(any(), any()))
			.thenReturn(Optional.empty());

		PeerComparison peerComparison = dbReader.buildPeerComparison(List.of(heldCard(FIRST_CARD_ID, FIRST_CARD_AT)));

		assertThat(peerComparison.status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
		assertThat(peerComparison.priceMoveId()).isNull();
		assertThat(peerComparison.yourMinutesToSell()).isNull();
	}

	@Test
	@DisplayName("확정 집계 행이 있으면 READY이고 yourMinutesToSell이 기준 카드의 minutesBeforeSell이다")
	void returnsReadyWithYourMinutesToSellTakenFromTheFirstCard() {
		when(priceMovePeerStatRepository.findByPriceMoveEventIdAndServiceDate(any(), any()))
			.thenReturn(Optional.of(peerStat(10, 3, 18)));

		PeerComparison peerComparison = dbReader.buildPeerComparison(List.of(heldCard(FIRST_CARD_ID, FIRST_CARD_AT)));

		assertThat(peerComparison.status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(peerComparison.priceMoveId()).isEqualTo(FIRST_CARD_ID);
		assertThat(peerComparison.holderCount()).isEqualTo(10);
		assertThat(peerComparison.soldWithin30MinRate()).isEqualByComparingTo("0.3000");
		assertThat(peerComparison.medianMinutesToSell()).isEqualTo(18);
		assertThat(peerComparison.yourMinutesToSell()).isEqualTo(110);
	}

	@Test
	@DisplayName("조회 두 메서드에 각각 @Transactional(readOnly = true)가 붙어 있다")
	void wrapsEachQueryInItsOwnReadOnlyTransaction() throws Exception {
		Method findHeldPriceMoves = CryptoPostSellFeedbackDbReader.class.getDeclaredMethod(
			"findHeldPriceMoves", Trade.class, LocalDateTime.class, LocalDateTime.class);
		Method buildPeerComparison = CryptoPostSellFeedbackDbReader.class.getDeclaredMethod(
			"buildPeerComparison", List.class);

		assertThat(findHeldPriceMoves.getAnnotation(Transactional.class)).isNotNull()
			.satisfies(annotation -> assertThat(annotation.readOnly()).isTrue());
		assertThat(buildPeerComparison.getAnnotation(Transactional.class)).isNotNull()
			.satisfies(annotation -> assertThat(annotation.readOnly()).isTrue());
	}

	private void givenCards(PriceMoveEvent... events) {
		when(priceMoveEventRepository.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
			any(), any(), any(), any()))
			.thenReturn(List.of(events));
	}

	private void givenNoSources() {
		when(priceMoveEventSourceRepository.findAllByPriceMoveEventIdIn(anyList())).thenReturn(List.of());
	}

	private static PriceMoveEvent card(Long id, LocalDateTime occurredAt) {
		PriceMoveEvent event = PriceMoveEvent.createCrypto(
			cryptoInstrument(), occurredAt, new BigDecimal("0.021"), new BigDecimal("3.0"),
			FIRST_CARD_ID.equals(id) ? "첫 카드" : "다음 카드", NarrativeSource.TEMPLATE, occurredAt);
		ReflectionTestUtils.setField(event, "id", id);
		return event;
	}

	private static PriceMoveEventSource sourceOf(PriceMoveEvent event, String title) {
		MarketNewsItem news = MarketNewsItem.create(
			cryptoInstrument(), MarketNewsItemType.NEWS, title, "coindesk.com",
			"https://news.example.test/" + title, FIRST_CARD_AT.minusMinutes(10), FIRST_CARD_AT);
		return PriceMoveEventSource.of(event, news);
	}

	private static HeldPriceMoveItem heldCard(Long id, LocalDateTime windowEnd) {
		return new HeldPriceMoveItem(
			id,
			windowEnd.minusMinutes(5),
			windowEnd,
			new BigDecimal("0.021"),
			PostSellArithmetic.minutesBetween(BUY_AT, windowEnd),
			PostSellArithmetic.minutesBetween(windowEnd, SELL_AT),
			"카드",
			List.of());
	}

	private static PriceMovePeerStat peerStat(int holderCount, int soldWithin30MinCount, Integer median) {
		return PriceMovePeerStat.create(
			card(FIRST_CARD_ID, FIRST_CARD_AT), SELL_DATE, holderCount, soldWithin30MinCount, median,
			SELL_DATE.plusDays(1).atStartOfDay());
	}

	private static Trade cryptoSellTrade() {
		Instrument instrument = cryptoInstrument();
		User user = User.create("crypto-trader@finplay.com", "password-hash", "ctrader", SELL_AT);
		Account account = Account.create(user, Market.CRYPTO, SELL_AT);
		Order order = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET, new BigDecimal("10"), "idem-key",
			"h".repeat(64), SELL_AT);
		Trade trade = Trade.of(
			order, account, instrument, null, OrderSide.SELL, new BigDecimal("68500"), new BigDecimal("10"),
			685_000L, 342L, -15_447L, SELL_AT, SELL_AT);
		ReflectionTestUtils.setField(trade, "id", 2L);
		return trade;
	}

	private static Instrument cryptoInstrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, SYMBOL, "비트코인", new BigDecimal("1"), 5_000L, true, SELL_AT);
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		return instrument;
	}
}
