package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.PriceMoveListResponse;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	TestClockConfig.class})
class PriceMoveQueryGateIntegrationTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalDate FIRST_REPLAY_DATE = LocalDate.of(2026, 8, 6);
	private static final LocalDate SECOND_REPLAY_DATE = LocalDate.of(2026, 8, 7);

	private static final LocalDateTime INITIAL_NOW = LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(10, 0));

	private static final String STOCK_SYMBOL = "005930";
	private static final String CRYPTO_SYMBOL = "BTC";

	@Autowired
	private PriceMoveQueryService priceMoveQueryService;

	@Autowired
	private PriceMoveCardService priceMoveCardService;

	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private TestClock clock;

	private TestClock mutableClock;

	private Instrument instrument;

	@BeforeEach
	void setUp() {
		mutableClock = clock;
		mutableClock.set(INITIAL_NOW);
		instrument = stockInstrument();
	}

	private Instrument stockInstrument() {
		return instrumentService.getInstrumentEntities(Market.STOCK).stream()
			.filter(each -> STOCK_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();
	}

	private void saveReadySession(LocalDate serviceDate) {
		stockReplaySessionRepository.save(StockReplaySession.ready(
			serviceDate,
			ORIGIN_TRADE_DATE,
			LocalDateTime.of(serviceDate, LocalTime.of(8, 40)),
			LocalDateTime.of(serviceDate, LocalTime.of(8, 0))));
	}

	private PriceMoveEvent saveCard(LocalTime windowStart, LocalTime windowEnd, LocalTime revealTime) {
		return saveCard(PriceMoveEventType.INTRADAY, windowStart, windowEnd, revealTime);
	}

	private PriceMoveEvent saveCard(
		PriceMoveEventType eventType, LocalTime windowStart, LocalTime windowEnd, LocalTime revealTime) {
		return priceMoveEventRepository.save(PriceMoveEvent.createStock(
			instrument,
			eventType,
			ORIGIN_TRADE_DATE,
			windowStart,
			windowEnd,
			new BigDecimal("-0.018200"),
			new BigDecimal("3.2500"),
			eventType + " " + windowStart + " 카드",
			NarrativeSource.LLM,
			revealTime,
			INITIAL_NOW));
	}

	private MarketNewsItem saveNews(String title, LocalDateTime publishedAt) {
		return marketNewsItemRepository.save(MarketNewsItem.create(
			instrument,
			MarketNewsItemType.NEWS,
			title,
			"테스트경제",
			"https://news.example.test/gate/" + title,
			publishedAt,
			publishedAt.plusMinutes(30)));
	}

	private List<PriceMoveItem> queryMoves() {
		return priceMoveQueryService.getPriceMoves(instrument.getId()).moves();
	}


	@Test
	@DisplayName("11:30에는 revealTime 11:26 카드만 보이고 14:10 카드는 안 보인다")
	void hidesCardsWhoseRevealTimeHasNotPassedYet() {
		saveReadySession(SECOND_REPLAY_DATE);
		saveCard(LocalTime.of(11, 20), LocalTime.of(11, 25), LocalTime.of(11, 26));
		saveCard(LocalTime.of(14, 5), LocalTime.of(14, 10), LocalTime.of(14, 10));
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(11, 30)));

		assertThat(queryMoves())
			.extracting(PriceMoveItem::windowEnd)
			.containsExactly(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 25)));
	}

	@Test
	@DisplayName("revealTime 정각에 카드가 열리고 1초 전에는 닫혀 있다")
	void opensExactlyAtRevealTime() {
		saveReadySession(SECOND_REPLAY_DATE);
		saveCard(LocalTime.of(11, 20), LocalTime.of(11, 25), LocalTime.of(11, 26));

		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(11, 25, 59)));
		assertThat(queryMoves()).isEmpty();

		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(11, 26, 0)));
		assertThat(queryMoves()).hasSize(1);
	}


	@Test
	@DisplayName("두 번째 재생일 오전 10:00에 오후 카드가 보이지 않는다")
	void keepsAfternoonCardHiddenOnTheMorningOfASecondReplay() {
		saveReadySession(FIRST_REPLAY_DATE);
		saveReadySession(SECOND_REPLAY_DATE);
		PriceMoveEvent afternoonCard = saveCard(LocalTime.of(14, 5), LocalTime.of(14, 10), LocalTime.of(14, 10));
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(10, 0)));

		LocalTime storedRevealTime = priceMoveEventRepository.findById(afternoonCard.getId()).orElseThrow()
			.getRevealTime();
		LocalDateTime asAbsoluteTime = LocalDateTime.of(ORIGIN_TRADE_DATE, storedRevealTime);
		assertThat(asAbsoluteTime).isBefore(LocalDateTime.now(mutableClock));

		Long openedByAbsoluteTimeGate = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM price_move_events "
				+ "WHERE id = ? AND TIMESTAMP(origin_trade_date, reveal_time) <= ?",
			Long.class,
			afternoonCard.getId(),
			LocalDateTime.now(mutableClock));
		assertThat(openedByAbsoluteTimeGate).isEqualTo(1L);

		assertThat(queryMoves()).isEmpty();
	}

	@Test
	@DisplayName("두 번째 재생일에도 오후 시각이 되면 같은 카드가 열린다 — 대칭이 맞는다")
	void opensTheSameCardOnTheAfternoonOfASecondReplay() {
		saveReadySession(FIRST_REPLAY_DATE);
		saveReadySession(SECOND_REPLAY_DATE);
		saveCard(LocalTime.of(14, 5), LocalTime.of(14, 10), LocalTime.of(14, 10));
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(15, 0)));

		assertThat(queryMoves()).hasSize(1);
	}

	@Test
	@DisplayName("windowStart·windowEnd에 붙는 날짜가 조회 날짜가 아니라 원본 거래일이다")
	void stampsWindowTimesWithTheOriginTradeDateNotTheQueryDate() {
		saveReadySession(FIRST_REPLAY_DATE);
		saveReadySession(SECOND_REPLAY_DATE);
		saveCard(LocalTime.of(14, 5), LocalTime.of(14, 10), LocalTime.of(14, 10));
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(15, 0)));

		PriceMoveListResponse response = priceMoveQueryService.getPriceMoves(instrument.getId());

		assertThat(response.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
		assertThat(response.moves()).singleElement().satisfies(move -> {
			assertThat(move.windowStart())
				.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 5)));
			assertThat(move.windowEnd())
				.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 10)));
			assertThat(move.windowStart().toLocalDate()).isNotEqualTo(SECOND_REPLAY_DATE);
			assertThat(move.windowEnd().toLocalDate()).isNotEqualTo(SECOND_REPLAY_DATE);
		});
	}


	@Test
	@DisplayName("전일 18:40 기사를 근거로 만든 갭 카드가 09:01 조회에 나오고 발행시각이 그대로 실린다")
	void showsOpeningGapCardRightAfterMarketOpenWithItsPreviousEveningSource() {
		saveReadySession(SECOND_REPLAY_DATE);
		LocalDateTime previousEvening = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 40));
		saveNews("전일 저녁 기사", previousEvening);
		PriceMoveEvent gapCard = priceMoveCardService.confirmStockCard(
			instrument,
			ORIGIN_TRADE_DATE,
			new PriceMoveDetectionDto(
				PriceMoveEventType.OPENING_GAP,
				LocalTime.of(9, 3),
				LocalTime.of(9, 3),
				new BigDecimal("0.030000"),
				new BigDecimal("3.0000")))
			.orElseThrow();
		assertThat(gapCard.getRevealTime()).isEqualTo(LocalTime.of(9, 0));

		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(9, 1)));

		assertThat(queryMoves()).singleElement().satisfies(move -> {
			assertThat(move.eventType()).isEqualTo(PriceMoveEventType.OPENING_GAP);
			assertThat(move.sources()).extracting(NewsItem::publishedAt).containsExactly(previousEvening);
			assertThat(move.sources()).extracting(NewsItem::title).containsExactly("전일 저녁 기사");
		});
	}


	@Test
	@DisplayName("카드가 0건이면 originTradeDate는 있고 moves는 빈 배열이며 status는 EMPTY다")
	void returnsOriginTradeDateWithEmptyMovesWhenThereIsNoCard() {
		saveReadySession(SECOND_REPLAY_DATE);

		PriceMoveListResponse response = priceMoveQueryService.getPriceMoves(instrument.getId());

		assertThat(response.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
		assertThat(response.moves()).isEmpty();
		assertThat(response.status()).isEqualTo(FeedbackContentStatus.EMPTY);
	}

	@Test
	@DisplayName("재생세션이 없거나 PREPARING·FAILED면 originTradeDate가 null이고 빈 배열이다")
	void returnsNullOriginTradeDateForEveryNotReadySessionState() {
		saveCard(LocalTime.of(9, 0), LocalTime.of(9, 5), LocalTime.of(9, 6));

		assertThat(priceMoveQueryService.getPriceMoves(instrument.getId()))
			.isEqualTo(PriceMoveListResponse.notYet());

		stockReplaySessionRepository.save(StockReplaySession.preparing(
			SECOND_REPLAY_DATE, ORIGIN_TRADE_DATE,
			LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(8, 0))));
		assertThat(priceMoveQueryService.getPriceMoves(instrument.getId()))
			.isEqualTo(PriceMoveListResponse.notYet());

		stockReplaySessionRepository.save(StockReplaySession.failed(
			FIRST_REPLAY_DATE, ORIGIN_TRADE_DATE,
			LocalDateTime.of(FIRST_REPLAY_DATE, LocalTime.of(8, 30)), "NO_DATA",
			LocalDateTime.of(FIRST_REPLAY_DATE, LocalTime.of(8, 0))));
		mutableClock.set(LocalDateTime.of(FIRST_REPLAY_DATE, LocalTime.of(15, 0)));
		assertThat(priceMoveQueryService.getPriceMoves(instrument.getId()))
			.isEqualTo(PriceMoveListResponse.notYet());
	}

	@Test
	@DisplayName("코인 종목은 예외 없이 originTradeDate가 null이고 빈 배열이다")
	void returnsEmptyResponseForCryptoInstrument() {
		saveReadySession(SECOND_REPLAY_DATE);
		Instrument crypto = instrumentService.getInstrumentEntities(Market.CRYPTO).stream()
			.filter(each -> CRYPTO_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();

		assertThat(priceMoveQueryService.getPriceMoves(crypto.getId()))
			.isEqualTo(PriceMoveListResponse.of(null, List.of()));
	}

	@Test
	@DisplayName("주식 재생세션 미준비(NOT_YET)와 코인 카드 0건(EMPTY)이 응답만으로 구별된다")
	void distinguishesNotReadyStockFromCryptoByStatusAlone() {
		Instrument crypto = instrumentService.getInstrumentEntities(Market.CRYPTO).stream()
			.filter(each -> CRYPTO_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();

		PriceMoveListResponse stockResponse = priceMoveQueryService.getPriceMoves(instrument.getId());
		PriceMoveListResponse cryptoResponse = priceMoveQueryService.getPriceMoves(crypto.getId());

		assertThat(stockResponse.originTradeDate()).isNull();
		assertThat(cryptoResponse.originTradeDate()).isNull();
		assertThat(stockResponse.moves()).isEmpty();
		assertThat(cryptoResponse.moves()).isEmpty();

		assertThat(stockResponse.status()).isEqualTo(FeedbackContentStatus.NOT_YET);
		assertThat(cryptoResponse.status()).isEqualTo(FeedbackContentStatus.EMPTY);
		assertThat(stockResponse).isNotEqualTo(cryptoResponse);
	}


	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	@Test
	@DisplayName("코인 분기 조회는 카드·근거가 있어도 원장 테이블을 전혀 건드리지 않는다")
	void neverWritesLedgerTablesWhenQueryingCryptoPriceMoves() {
		Instrument crypto = instrumentService.getInstrumentEntities(Market.CRYPTO).stream()
			.filter(each -> CRYPTO_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();
		LocalDateTime occurredAt = INITIAL_NOW.minusHours(1);
		PriceMoveEvent card = priceMoveEventRepository.save(PriceMoveEvent.createCrypto(
			crypto, occurredAt, new BigDecimal("0.031000"), new BigDecimal("3.4000"),
			"대형 거래소 상장 소식이 있었습니다.", NarrativeSource.TEMPLATE, INITIAL_NOW));
		MarketNewsItem news = marketNewsItemRepository.save(MarketNewsItem.create(
			crypto, MarketNewsItemType.NEWS, "코인 원장 불변 기사", "coindesk.com",
			"https://news.example.test/ledger-invariance", occurredAt.minusMinutes(10), INITIAL_NOW));
		priceMoveEventSourceRepository.save(PriceMoveEventSource.of(card, news));

		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);

		PriceMoveListResponse response = priceMoveQueryService.getPriceMoves(crypto.getId());

		assertThat(response.moves()).hasSize(1);
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
	}

	private Map<String, Long> rowCounts(List<String> tables) {
		entityManager.flush();
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : tables) {
			counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		return counts;
	}


	@Test
	@DisplayName("windowStart가 똑같이 09:00인 갭·장중 카드가 저장 순서(id)대로 내려온다")
	void breaksWindowStartTiesByIdWhichIsCreationOrder() {
		saveReadySession(SECOND_REPLAY_DATE);
		PriceMoveEvent gap = saveCard(PriceMoveEventType.OPENING_GAP, LocalTime.of(9, 0), LocalTime.of(9, 0),
			LocalTime.of(9, 0));
		PriceMoveEvent intraday = saveCard(PriceMoveEventType.INTRADAY, LocalTime.of(9, 0), LocalTime.of(9, 5),
			LocalTime.of(9, 6));
		assertThat(gap.getWindowStart()).isEqualTo(intraday.getWindowStart()).isEqualTo(LocalTime.of(9, 0));
		assertThat(gap.getId()).isLessThan(intraday.getId());
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(15, 0)));

		assertThat(queryMoves())
			.extracting(PriceMoveItem::eventType)
			.containsExactly(PriceMoveEventType.OPENING_GAP, PriceMoveEventType.INTRADAY);
	}

	@Test
	@DisplayName("저장 순서를 뒤집으면 응답 순서도 뒤집힌다 — 2차 키가 event_type이 아니라 id다")
	void tieOrderFollowsInsertionOrderNotEventType() {
		saveReadySession(SECOND_REPLAY_DATE);
		PriceMoveEvent intraday = saveCard(PriceMoveEventType.INTRADAY, LocalTime.of(9, 0), LocalTime.of(9, 5),
			LocalTime.of(9, 6));
		PriceMoveEvent gap = saveCard(PriceMoveEventType.OPENING_GAP, LocalTime.of(9, 0), LocalTime.of(9, 0),
			LocalTime.of(9, 0));
		assertThat(intraday.getId()).isLessThan(gap.getId());
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(15, 0)));

		assertThat(queryMoves())
			.extracting(PriceMoveItem::eventType)
			.containsExactly(PriceMoveEventType.INTRADAY, PriceMoveEventType.OPENING_GAP);
	}

	@Test
	@DisplayName("카드는 windowStart 오름차순이고 근거는 발행시각 내림차순이다")
	void ordersCardsByWindowStartAndSourcesByPublishedAtDesc() {
		saveReadySession(SECOND_REPLAY_DATE);
		saveNews("이른 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)));
		saveNews("늦은 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 24)));
		priceMoveCardService.confirmStockCard(instrument, ORIGIN_TRADE_DATE, new PriceMoveDetectionDto(
			PriceMoveEventType.INTRADAY, LocalTime.of(11, 20), LocalTime.of(11, 25),
			new BigDecimal("-0.018200"), new BigDecimal("3.2500")));
		saveCard(LocalTime.of(9, 0), LocalTime.of(9, 5), LocalTime.of(9, 6));
		mutableClock.set(LocalDateTime.of(SECOND_REPLAY_DATE, LocalTime.of(15, 0)));

		List<PriceMoveItem> moves = queryMoves();

		assertThat(moves).extracting(move -> move.windowStart().toLocalTime())
			.containsExactly(LocalTime.of(9, 0), LocalTime.of(11, 20));
		assertThat(moves.get(1).sources()).extracting(NewsItem::title)
			.containsExactly("늦은 기사", "이른 기사");
	}

}
