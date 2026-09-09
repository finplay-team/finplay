package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.entity.PriceMovePeerStat;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
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
import java.util.List;
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
class CryptoPeerStatsBatchIntegrationTest {

	private static final LocalDate CARD_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate BATCH_RUN_DATE = CARD_DATE.plusDays(1);

	private static final LocalDateTime BATCH_AT = LocalDateTime.of(BATCH_RUN_DATE, LocalTime.of(0, 5));

	private static final LocalDateTime CARD_AT = LocalDateTime.of(CARD_DATE, LocalTime.of(14, 0));

	private static final LocalDateTime BUY_AT = LocalDateTime.of(CARD_DATE, LocalTime.of(9, 0));
	private static final LocalDateTime SELL_AT = LocalDateTime.of(CARD_DATE, LocalTime.of(15, 0));

	@Autowired
	private PeerStatsBatchService peerStatsBatchService;

	@Autowired
	private PostSellFeedbackReader postSellFeedbackReader;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

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
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceMovePeerStatRepository priceMovePeerStatRepository;

	@Autowired
	private TestClock clock;

	private Instrument coin;

	private int memberSeq = 0;

	@BeforeEach
	void setUp() {
		clock.set(BATCH_AT);
		coin = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, "BTC275", "테스트코인275", BigDecimal.valueOf(1), 5_000L, true, CARD_AT));
	}

	@Test
	@DisplayName("service_date가 배치 실행일이 아니라 카드 occurred_at의 KST 날짜로 저장된다")
	void storesServiceDateFromTheCardOccurredAtNotTheBatchRunDate() {
		PriceMoveEvent card = givenCryptoCard(CARD_AT);
		givenHolderWhoSellsAfter(10);
		givenHolderWhoSellsAfter(30);
		givenHolderWhoSellsAfter(45);
		givenHolderWhoSellsAfter(100);
		givenHolderWhoNeverSells();

		peerStatsBatchService.runCryptoPeerStatsBatch();

		List<PriceMovePeerStat> stats = priceMovePeerStatRepository.findAll();
		assertThat(stats).hasSize(1);
		PriceMovePeerStat stat = stats.get(0);
		assertThat(stat.getPriceMoveEvent().getId()).isEqualTo(card.getId());
		assertThat(stat.getServiceDate())
			.as("실행일(%s)을 쓰면 조회 키와 하루 어긋나 peerComparison이 영원히 NOT_YET이다", BATCH_RUN_DATE)
			.isEqualTo(CARD_DATE)
			.isNotEqualTo(BATCH_RUN_DATE);

		assertThat(stat.getHolderCount()).isEqualTo(5);
		assertThat(stat.getSoldWithin30MinCount()).isEqualTo(2);
		assertThat(stat.getMedianMinutesToSell()).isEqualTo(37);
	}

	@Test
	@DisplayName("T가 분 경계면 매도 시각에 초가 붙어도 30분 경계와 중앙값이 본인 값과 같은 규칙으로 나온다")
	void secondsInTheSellTimeDoNotShiftTheBoundaryWhenTIsOnTheMinute() {
		givenCryptoCard(CARD_AT);
		givenHolderWhoSellsAt(CARD_AT.plusMinutes(30).plusSeconds(20));
		givenHolderWhoSellsAt(CARD_AT.plusMinutes(29).plusSeconds(40));
		givenHolderWhoSellsAt(CARD_AT.plusMinutes(31).plusSeconds(20));
		givenHolderWhoSellsAt(CARD_AT.plusMinutes(45).plusSeconds(59));
		givenHolderWhoNeverSells();

		peerStatsBatchService.runCryptoPeerStatsBatch();

		PriceMovePeerStat stat = priceMovePeerStatRepository.findAll().get(0);
		assertThat(stat.getHolderCount()).isEqualTo(5);
		assertThat(stat.getSoldWithin30MinCount())
			.as("T에 초가 붙어 있었다면 29분 40초 매도가 28분으로 밀려 경계 판정이 달라진다")
			.isEqualTo(2);
		assertThat(stat.getMedianMinutesToSell()).isEqualTo(30);
	}

	@Test
	@DisplayName("배치가 저장한 행을 코인 조회 경로가 찾아 peerComparison이 NOT_YET에서 벗어난다")
	void readerFindsTheRowStoredByTheCryptoBatch() {
		PriceMoveEvent card = givenCryptoCard(CARD_AT);
		Trade sellTrade = givenOwnSellTradeCoveringTheCard();
		givenHolderWhoSellsAfter(10);
		givenHolderWhoSellsAfter(30);
		givenHolderWhoSellsAfter(45);
		givenHolderWhoNeverSells();

		PeerComparison beforeBatch = readOwnFeedback(sellTrade).peerComparison();
		assertThat(beforeBatch.status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);

		peerStatsBatchService.runCryptoPeerStatsBatch();

		PeerComparison afterBatch = readOwnFeedback(sellTrade).peerComparison();
		assertThat(afterBatch.status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(afterBatch.priceMoveId()).isEqualTo(card.getId());
		assertThat(afterBatch.holderCount()).isEqualTo(5);
		assertThat(afterBatch.yourMinutesToSell()).isEqualTo(60);
	}

	@Test
	@DisplayName("전날 KST 하루 안의 코인 카드만 집계하고 그제·당일 카드는 제외한다")
	void aggregatesOnlyCardsWithinThePreviousKstDay() {
		PriceMoveEvent dayBeforeYesterday = givenCryptoCard(CARD_AT.minusDays(1));
		PriceMoveEvent firstInstant = givenCryptoCard(CARD_DATE.atStartOfDay());
		PriceMoveEvent middle = givenCryptoCard(CARD_AT);
		PriceMoveEvent lastInstant = givenCryptoCard(
			BATCH_RUN_DATE.atStartOfDay().minusNanos(1_000L));
		PriceMoveEvent today = givenCryptoCard(LocalDateTime.of(BATCH_RUN_DATE, LocalTime.of(0, 2)));
		givenHolderWhoSellsAfter(10);

		peerStatsBatchService.runCryptoPeerStatsBatch();

		assertThat(priceMovePeerStatRepository.findAll())
			.extracting(stat -> stat.getPriceMoveEvent().getId())
			.containsExactlyInAnyOrder(firstInstant.getId(), middle.getId(), lastInstant.getId())
			.doesNotContain(dayBeforeYesterday.getId(), today.getId());
	}

	@Test
	@DisplayName("카드마다 자기 occurred_at의 날짜가 service_date가 된다")
	void usesEachCardsOwnOccurredAtDateAsItsServiceDate() {
		givenCryptoCard(CARD_DATE.atStartOfDay());
		givenCryptoCard(BATCH_RUN_DATE.atStartOfDay().minusNanos(1_000L));
		givenHolderWhoSellsAfter(10);

		peerStatsBatchService.runCryptoPeerStatsBatch();

		assertThat(priceMovePeerStatRepository.findAll())
			.extracting(PriceMovePeerStat::getServiceDate)
			.containsOnly(CARD_DATE);
	}

	@Test
	@DisplayName("재생세션이 READY가 아니어도 코인 배치는 정상 집계한다")
	void aggregatesEvenWhenTheReplaySessionIsNotReady() {
		stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.preparing(BATCH_RUN_DATE, CARD_DATE, BATCH_AT));
		givenCryptoCard(CARD_AT);
		givenHolderWhoSellsAfter(10);

		peerStatsBatchService.runCryptoPeerStatsBatch();

		assertThat(priceMovePeerStatRepository.findAll()).hasSize(1);
	}

	@Test
	@DisplayName("재생세션이 아예 없어도 코인 배치는 정상 집계한다")
	void aggregatesEvenWhenThereIsNoReplaySessionAtAll() {
		givenCryptoCard(CARD_AT);
		givenHolderWhoSellsAfter(10);

		peerStatsBatchService.runCryptoPeerStatsBatch();

		assertThat(priceMovePeerStatRepository.findAll()).hasSize(1);
	}

	@Test
	@DisplayName("같은 날 두 번 실행해도 확정 집계가 중복 저장되지 않는다")
	void doesNotDuplicateWhenRunTwiceOnTheSameDay() {
		givenCryptoCard(CARD_AT);
		givenHolderWhoSellsAfter(10);

		peerStatsBatchService.runCryptoPeerStatsBatch();
		List<Long> firstRunIds = priceMovePeerStatRepository.findAll().stream()
			.map(PriceMovePeerStat::getId)
			.toList();
		assertThat(firstRunIds).hasSize(1);

		peerStatsBatchService.runCryptoPeerStatsBatch();

		assertThat(priceMovePeerStatRepository.findAll())
			.extracting(PriceMovePeerStat::getId)
			.isEqualTo(firstRunIds);
	}

	@Test
	@DisplayName("코인 배치는 주식 카드에 대한 확정 집계를 만들지 않는다")
	void neverAggregatesStockCards() {
		Instrument stock = instrumentRepository.saveAndFlush(Instrument.create(
			Market.STOCK, "ST275", "테스트종목275", BigDecimal.valueOf(100), 10_000L, true, CARD_AT));
		PriceMoveEvent stockCard = priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock, PriceMoveEventType.INTRADAY, CARD_DATE,
			LocalTime.of(9, 5), LocalTime.of(9, 10),
			BigDecimal.valueOf(0.01), BigDecimal.valueOf(3.0),
			"주식 카드", NarrativeSource.TEMPLATE, LocalTime.of(9, 30), CARD_AT));
		PriceMoveEvent cryptoCard = givenCryptoCard(CARD_AT);
		givenHolderWhoSellsAfter(10);

		peerStatsBatchService.runCryptoPeerStatsBatch();

		assertThat(priceMovePeerStatRepository.findAll())
			.extracting(stat -> stat.getPriceMoveEvent().getId())
			.containsExactly(cryptoCard.getId())
			.doesNotContain(stockCard.getId());
	}

	private PostSellFeedbackResponse readOwnFeedback(Trade sellTrade) {
		return postSellFeedbackReader.read(sellTrade.getAccount().getUser().getId(), sellTrade.getId());
	}

	private PriceMoveEvent givenCryptoCard(LocalDateTime occurredAt) {
		return priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createCrypto(
			coin, occurredAt, BigDecimal.valueOf(0.021), BigDecimal.valueOf(3.0),
			"코인 테스트 카드", NarrativeSource.TEMPLATE, occurredAt));
	}

	private Trade givenOwnSellTradeCoveringTheCard() {
		Holding holding = createHolding();
		HoldingLot lot = createBuyLot(holding, BigDecimal.valueOf(10), BUY_AT);
		Trade sellTrade = createSellTrade(holding.getAccount(), BigDecimal.valueOf(10), SELL_AT);
		allocate(sellTrade, lot, BigDecimal.valueOf(10));
		return sellTrade;
	}

	private void givenHolderWhoSellsAfter(int minutesAfterT) {
		Holding holding = createHolding();
		HoldingLot lot = createBuyLot(holding, BigDecimal.valueOf(10), CARD_AT.minusHours(3));
		Trade sellTrade = createSellTrade(
			holding.getAccount(), BigDecimal.valueOf(10), CARD_AT.plusMinutes(minutesAfterT));
		allocate(sellTrade, lot, BigDecimal.valueOf(10));
	}

	private void givenHolderWhoSellsAt(LocalDateTime sellAt) {
		Holding holding = createHolding();
		HoldingLot lot = createBuyLot(holding, BigDecimal.valueOf(10), CARD_AT.minusHours(3));
		Trade sellTrade = createSellTrade(holding.getAccount(), BigDecimal.valueOf(10), sellAt);
		allocate(sellTrade, lot, BigDecimal.valueOf(10));
	}

	private void givenHolderWhoNeverSells() {
		Holding holding = createHolding();
		createBuyLot(holding, BigDecimal.valueOf(10), CARD_AT.minusHours(3));
	}

	private Holding createHolding() {
		memberSeq++;
		User user = userRepository.saveAndFlush(
			User.create("crypto-peer" + memberSeq + "@finplay.com", "hash", "cpeer" + memberSeq, CARD_AT));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, CARD_AT));
		return holdingRepository.saveAndFlush(Holding.create(account, coin, CARD_AT));
	}

	private HoldingLot createBuyLot(Holding holding, BigDecimal quantity, LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			holding.getAccount().getUser(), holding.getAccount(), coin, OrderSide.BUY, OrderType.MARKET,
			quantity, "idem-cbuy-" + System.nanoTime(), "a".repeat(64), executedAt));
		Trade buyTrade = tradeRepository.saveAndFlush(Trade.of(
			order, holding.getAccount(), coin, null, OrderSide.BUY,
			BigDecimal.valueOf(70000), quantity,
			70000L * quantity.longValueExact(), 100L, null, executedAt, executedAt));
		return holdingLotRepository.saveAndFlush(
			HoldingLot.create(holding, buyTrade, quantity, BigDecimal.valueOf(70000), 100L, executedAt, executedAt));
	}

	private Trade createSellTrade(Account account, BigDecimal quantity, LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			account.getUser(), account, coin, OrderSide.SELL, OrderType.MARKET,
			quantity, "idem-csell-" + System.nanoTime(), "b".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, coin, null, OrderSide.SELL,
			BigDecimal.valueOf(75000), quantity,
			75000L * quantity.longValueExact(), 100L, 49_900L, executedAt, executedAt));
	}

	private void allocate(Trade sellTrade, HoldingLot lot, BigDecimal quantity) {
		lot.consume(quantity);
		holdingLotRepository.saveAndFlush(lot);
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sellTrade, lot, quantity, 700_000L, 100L, sellTrade.getExecutedAt()));
	}
}
