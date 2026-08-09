// 고정 Clock + Testcontainers로 코인 집단 비교 확정 집계 배치(runCryptoPeerStatsBatch)의 종단과 조회 키 정합을 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.feedback.domain.PriceMovePeerStat;
import com.finplay.api.feedback.dto.response.PeerComparison;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.domain.HoldingLot;
import com.finplay.api.portfolio.domain.TradeAllocation;
import com.finplay.api.portfolio.repository.HoldingLotRepository;
import com.finplay.api.portfolio.repository.HoldingRepository;
import com.finplay.api.portfolio.repository.TradeAllocationRepository;
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

// tasks-275.md 3번 항목이다. 검증 대상은 PeerStatsBatchService.runCryptoPeerStatsBatch(§FEED-012 결정 3)와,
// 그것이 저장한 행을 CryptoPostSellFeedbackReader.buildPeerComparison이 실제로 찾는가다.
//
// PeerStatsBatchServiceIntegrationTest(주식)와 같은 픽스처 자산을 쓰지만 코인 케이스를 그 파일에 넣지 않는다 —
// 거기는 setUp에서 재생세션을 준비하고 있어 "코인은 세션을 보지 않는다"를 같은 파일에서 보이기 어렵다.
// 그 파일은 이 항목에서 수정하지 않는다(주식 회귀는 그 파일이 그대로 통과하는 것으로 확인한다).
//
// LLM은 부르지 않는다 — 이 배치도, 아래에서 부르는 PostSellFeedbackReader도 서술을 만들지 않는다.
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class CryptoPeerStatsBatchIntegrationTest {

	// 카드가 발생한 날(집계 대상)과 배치가 도는 날이 다르다 — 이 파일의 거의 모든 단정이 이 차이 위에 있다.
	private static final LocalDate CARD_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate BATCH_RUN_DATE = CARD_DATE.plusDays(1);

	// 배치 실행 시각 — §C-1의 크론대로 전날이 끝난 직후인 다음 날 00:05다.
	private static final LocalDateTime BATCH_AT = LocalDateTime.of(BATCH_RUN_DATE, LocalTime.of(0, 5));

	// 기준 카드의 occurred_at. 코인 카드는 window_start/window_end가 NULL이고 이 값이 곧 T이자 windowEnd다(§C-9).
	private static final LocalDateTime CARD_AT = LocalDateTime.of(CARD_DATE, LocalTime.of(14, 0));

	// 조회자의 보유 구간 — 카드(14:00)를 감싼다. 매도는 카드보다 60분 뒤다.
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

	// 전역 Clock 빈을 대신하는 공용 테스트 시계 (TestClockConfig).
	@Autowired
	private TestClock clock;

	private Instrument coin;

	private int memberSeq = 0;

	@BeforeEach
	void setUp() {
		// 배치가 "전날"을 계산하는 기준이자, 아래 조회에서 §C-5 게이트를 판정하는 기준이다.
		clock.set(BATCH_AT);
		coin = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, "BTC275", "테스트코인275", BigDecimal.valueOf(1), 5_000L, true, CARD_AT));
	}

	// --- service_date 규칙 (이 항목의 핵심 1) ---

	// 함정 재현 — 배치는 자정을 넘겨 돌기 때문에 "실행일"과 "카드 날짜"가 다르다. 두 날짜가 같은 픽스처를 쓰면
	// LocalDate.now(clock)을 저장하는 잘못된 구현도 초록이 되므로, 여기서는 반드시 하루 어긋난 시각으로 돌린다.
	@Test
	@DisplayName("service_date가 배치 실행일이 아니라 카드 occurred_at의 KST 날짜로 저장된다")
	void storesServiceDateFromTheCardOccurredAtNotTheBatchRunDate() {
		PriceMoveEvent card = givenCryptoCard(CARD_AT);
		// T = 14:00. 10·30분 후 매도(30분 내), 45·100분 후(30분 밖), 1명 미매도 →
		// holderCount=5, soldWithin30MinCount=2, minutesToSell=[10,30,45,100] → median=(30+45)/2=37.
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

		// T가 occurredAt 그대로여야 나오는 값들이다 — T를 다른 시각으로 잡으면 세 지표가 조용히 달라진다.
		assertThat(stat.getHolderCount()).isEqualTo(5);
		assertThat(stat.getSoldWithin30MinCount()).isEqualTo(2);
		assertThat(stat.getMedianMinutesToSell()).isEqualTo(37);
	}

	// --- 조회 키 정합 (이 항목의 핵심 2) ---

	// 저장 키(배치)와 조회 키(리더)가 어긋나도 컴파일·실행이 모두 성공하고 status만 조용히 NOT_YET으로 굳는다.
	// 두 규칙을 각각 단정하는 것으로는 못 잡는다 — 실제로 저장한 행을 실제 조회 경로가 찾아내는지를 봐야 한다.
	@Test
	@DisplayName("배치가 저장한 행을 코인 조회 경로가 찾아 peerComparison이 NOT_YET에서 벗어난다")
	void readerFindsTheRowStoredByTheCryptoBatch() {
		PriceMoveEvent card = givenCryptoCard(CARD_AT);
		Trade sellTrade = givenOwnSellTradeCoveringTheCard();
		// 조회자 본인을 포함해 5명이어야 INSUFFICIENT_SAMPLE이 아니라 READY까지 간다(§C-4, 최소 표본 5).
		givenHolderWhoSellsAfter(10);
		givenHolderWhoSellsAfter(30);
		givenHolderWhoSellsAfter(45);
		givenHolderWhoNeverSells();

		// 배치 전에는 행이 없어 NOT_YET이다 — 아래 단정이 "원래부터 READY였다"가 아님을 여기서 못박는다.
		PeerComparison beforeBatch = readOwnFeedback(sellTrade).peerComparison();
		assertThat(beforeBatch.status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);

		peerStatsBatchService.runCryptoPeerStatsBatch();

		PeerComparison afterBatch = readOwnFeedback(sellTrade).peerComparison();
		assertThat(afterBatch.status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(afterBatch.priceMoveId()).isEqualTo(card.getId());
		assertThat(afterBatch.holderCount()).isEqualTo(5);
		// yourMinutesToSell = 매도(15:00) − 카드(14:00).
		assertThat(afterBatch.yourMinutesToSell()).isEqualTo(60);
	}

	// --- 대상 범위 ---

	@Test
	@DisplayName("전날 KST 하루 안의 코인 카드만 집계하고 그제·당일 카드는 제외한다")
	void aggregatesOnlyCardsWithinThePreviousKstDay() {
		PriceMoveEvent dayBeforeYesterday = givenCryptoCard(CARD_AT.minusDays(1));
		PriceMoveEvent firstInstant = givenCryptoCard(CARD_DATE.atStartOfDay());
		PriceMoveEvent middle = givenCryptoCard(CARD_AT);
		// DATETIME(6)이 담을 수 있는 그날의 마지막 값 — 이 카드가 빠지면 자정 직전 카드가 매일 조용히 사라진다.
		PriceMoveEvent lastInstant = givenCryptoCard(
			BATCH_RUN_DATE.atStartOfDay().minusNanos(1_000L));
		// 배치가 도는 그 시각(00:05)보다 앞선 당일 카드다 — "오늘"까지 긁으면 여기서 걸린다.
		PriceMoveEvent today = givenCryptoCard(LocalDateTime.of(BATCH_RUN_DATE, LocalTime.of(0, 2)));
		givenHolderWhoSellsAfter(10);

		peerStatsBatchService.runCryptoPeerStatsBatch();

		assertThat(priceMovePeerStatRepository.findAll())
			.extracting(stat -> stat.getPriceMoveEvent().getId())
			.containsExactlyInAnyOrder(firstInstant.getId(), middle.getId(), lastInstant.getId())
			.doesNotContain(dayBeforeYesterday.getId(), today.getId());
	}

	// 경계 카드의 service_date도 각자의 occurred_at 날짜여야 한다 — 하나의 targetDate를 모든 행에 쓰는 구현이면
	// 위 범위 테스트는 통과하지만 이 단정에서 갈린다.
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

	// --- 재생세션 독립 ---

	// 주식 진입점은 첫 줄에서 세션 READY를 확인하고 아니면 0건으로 끝난다. 코인에 그 확인이 섞이면 재생이
	// 준비되지 않은 날 코인 집계가 통째로 사라지는데, 예외도 로그도 실패도 없다.
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

	// --- 중복 ---

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

	// --- 주식 회귀 ---

	// 두 진입점이 한 클래스에 있어 대상 조회를 잘못 넓히기 쉽다. 주식 카드는 occurred_at이 NULL이라 시장 조건이
	// 빠져도 걸리지 않지만, 그 사실 자체가 이 단정 없이는 확인되지 않는다.
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

	// --- 픽스처 ---

	private PostSellFeedbackResponse readOwnFeedback(Trade sellTrade) {
		return postSellFeedbackReader.read(sellTrade.getAccount().getUser().getId(), sellTrade.getId());
	}

	private PriceMoveEvent givenCryptoCard(LocalDateTime occurredAt) {
		return priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createCrypto(
			coin, occurredAt, BigDecimal.valueOf(0.021), BigDecimal.valueOf(3.0),
			"코인 테스트 카드", NarrativeSource.TEMPLATE, occurredAt));
	}

	/**
	 * 조회 경로에서 쓸 "본인 매도 체결"을 만든다 — 보유 구간이 {@link #CARD_AT}를 감싸므로 조회 응답의
	 * {@code priceMoves}에 그 카드가 들어오고, 그 카드가 곧 집단 비교의 기준 카드가 된다.
	 *
	 * <p>이 보유자도 T 시점 모집단의 한 명이다(매수는 T 이전, 매도는 T 이후).
	 */
	private Trade givenOwnSellTradeCoveringTheCard() {
		Holding holding = createHolding();
		HoldingLot lot = createBuyLot(holding, BigDecimal.valueOf(10), BUY_AT);
		Trade sellTrade = createSellTrade(holding.getAccount(), BigDecimal.valueOf(10), SELL_AT);
		allocate(sellTrade, lot, BigDecimal.valueOf(10));
		return sellTrade;
	}

	// T 시점 이전에 사서, T + minutesAfterT 뒤에 파는 보유자 1명을 만든다.
	private void givenHolderWhoSellsAfter(int minutesAfterT) {
		Holding holding = createHolding();
		HoldingLot lot = createBuyLot(holding, BigDecimal.valueOf(10), CARD_AT.minusHours(3));
		Trade sellTrade = createSellTrade(
			holding.getAccount(), BigDecimal.valueOf(10), CARD_AT.plusMinutes(minutesAfterT));
		allocate(sellTrade, lot, BigDecimal.valueOf(10));
	}

	// T 시점 이전에 사서 계속 보유 중인(미매도) 보유자 1명을 만든다.
	private void givenHolderWhoNeverSells() {
		Holding holding = createHolding();
		createBuyLot(holding, BigDecimal.valueOf(10), CARD_AT.minusHours(3));
	}

	private Holding createHolding() {
		memberSeq++;
		User user = userRepository.saveAndFlush(
			User.create("crypto-peer" + memberSeq + "@finplay.com", "hash", "cpeer" + memberSeq, CARD_AT));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, CARD_AT));
		return holdingRepository.saveAndFlush(Holding.create(account, coin, CARD_AT));
	}

	// 코인 체결이라 stockReplaySession이 null이다 — Trade.of가 코인에 세션을 주면 거부한다.
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
