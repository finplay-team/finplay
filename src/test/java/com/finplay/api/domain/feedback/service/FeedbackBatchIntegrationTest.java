package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.domain.feedback.repository.MarketBriefingRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class FeedbackBatchIntegrationTest {

	private static final LocalDateTime BATCH_AT = LocalDateTime.of(2026, 8, 6, 8, 45);

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	private static final String STOCK_SYMBOL = "005930";

	private static final long[] CLOSES = {
		10000, 10002, 10004, 10006, 10008, 10010, 10012, 10120, 10230, 10340, 10450};

	private static final BigDecimal FIRST_CANDLE_OPEN = new BigDecimal("10000");
	private static final BigDecimal PREVIOUS_CLOSE = new BigDecimal("9700");

	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	private static final List<String> READ_ONLY_TABLES = List.of("instruments", "stock_candles", "market_news_items",
		"stock_replay_sessions");

	private static final List<String> BATCH_OUTPUT_TABLES = List.of("price_move_events", "price_move_event_sources",
		"instrument_news_summaries", "market_briefings");

	private static final List<String> OTHER_FEEDBACK_TABLES = List.of("trade_feedbacks", "price_move_peer_stats");

	@Autowired
	private FeedbackBatchService feedbackBatchService;

	@Autowired
	private StockReplayService stockReplayService;

	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	@Autowired
	private InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	@Autowired
	private MarketBriefingRepository marketBriefingRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TestClock clock;

	private Instrument instrument;

	@BeforeEach
	void setUp() {
		clock.set(BATCH_AT);
		instrument = instrumentService.getInstrumentEntities(Market.STOCK).stream()
			.filter(each -> STOCK_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();


		saveCandle(PREVIOUS_TRADE_DATE, LocalTime.of(15, 27), PREVIOUS_CLOSE, PREVIOUS_CLOSE);
		for (int minute = 0; minute < CLOSES.length; minute++) {
			BigDecimal close = BigDecimal.valueOf(CLOSES[minute]);
			BigDecimal open = minute == 0 ? FIRST_CANDLE_OPEN : close;
			saveCandle(ORIGIN_TRADE_DATE, LocalTime.of(9, 0).plusMinutes(minute), open, close);
		}

		saveNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveNews("장중 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 8)));
	}

	private void saveCandle(LocalDate tradingDate, LocalTime candleTime, BigDecimal open, BigDecimal close) {
		stockCandleRepository.save(StockCandle.create(
			instrument, tradingDate, candleTime, open, open.max(close), open.min(close), close,
			1000L, "KRX_REPLAY", BATCH_AT));
	}

	private void givenReadyReplaySession() {
		stockReplaySessionRepository.save(StockReplaySession.ready(
			BATCH_AT.toLocalDate(),
			ORIGIN_TRADE_DATE,
			LocalDateTime.of(BATCH_AT.toLocalDate(), LocalTime.of(8, 40)),
			LocalDateTime.of(BATCH_AT.toLocalDate(), LocalTime.of(8, 0))));
	}

	private void saveNews(String title, LocalDateTime publishedAt) {
		marketNewsItemRepository.save(MarketNewsItem.create(
			instrument,
			MarketNewsItemType.NEWS,
			title,
			"테스트경제",
			"https://news.example.test/batch/" + title,
			publishedAt,
			publishedAt.plusMinutes(30)));
	}

	@Test
	@DisplayName("개장 전 08:45에 배치가 하루치 분봉을 받아 카드를 실제로 생성한다")
	void createsCardsFromFullDayCandlesAtPreMarketTime() {
		givenReadyReplaySession();
		assertThat(stockReplayService.getRevealedCandles(instrument.getId(), null, null)).isEmpty();
		assertThat(stockReplayService.getFullDayCandles(instrument.getId(), ORIGIN_TRADE_DATE))
			.hasSize(CLOSES.length);

		feedbackBatchService.runPreMarketBatch();

		List<PriceMoveEvent> cards = cardsForFixture();
		assertThat(cards).as("카드가 0건이면 배치가 하루치 분봉을 못 받은 것이다").isNotEmpty();
		assertThat(cards)
			.extracting(PriceMoveEvent::getEventType, PriceMoveEvent::getWindowStart,
				PriceMoveEvent::getWindowEnd)
			.containsExactlyInAnyOrder(
				org.assertj.core.groups.Tuple.tuple(
					PriceMoveEventType.OPENING_GAP, LocalTime.of(9, 0), LocalTime.of(9, 0)),
				org.assertj.core.groups.Tuple.tuple(
					PriceMoveEventType.INTRADAY, LocalTime.of(9, 5), LocalTime.of(9, 10)));
		assertThat(priceMoveEventSourceRepository.count()).isGreaterThanOrEqualTo(cards.size());
	}

	@Test
	@DisplayName("같은 서비스 날짜에 두 번 실행해도 카드가 중복 생성되지 않는다")
	void doesNotDuplicateCardsWhenRunTwiceOnTheSameServiceDate() {
		givenReadyReplaySession();

		feedbackBatchService.runPreMarketBatch();
		List<Long> firstRunIds = cardsForFixture().stream().map(PriceMoveEvent::getId).sorted().toList();
		long sourcesAfterFirstRun = priceMoveEventSourceRepository.count();
		assertThat(firstRunIds).isNotEmpty();

		feedbackBatchService.runPreMarketBatch();

		assertThat(cardsForFixture().stream().map(PriceMoveEvent::getId).sorted().toList())
			.isEqualTo(firstRunIds);
		assertThat(priceMoveEventSourceRepository.count()).isEqualTo(sourcesAfterFirstRun);
	}

	@Test
	@DisplayName("재생세션이 PREPARING이면 카드가 하나도 생기지 않고 예외도 없다")
	void createsNothingWhenTheReplaySessionIsNotReady() {
		stockReplaySessionRepository.save(StockReplaySession.preparing(
			BATCH_AT.toLocalDate(),
			ORIGIN_TRADE_DATE,
			LocalDateTime.of(BATCH_AT.toLocalDate(), LocalTime.of(8, 0))));

		feedbackBatchService.runPreMarketBatch();

		assertThat(priceMoveEventRepository.findAll()).isEmpty();
		assertThat(priceMoveEventSourceRepository.count()).isZero();
	}

	@Test
	@DisplayName("배치가 네 산출물 테이블에만 쓰고 원장·읽기 전용·다른 피드백 테이블은 그대로다")
	void neverWritesOutsideTheFourBatchOutputTables() {
		givenReadyReplaySession();
		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		Map<String, Long> readOnlyBefore = rowCounts(READ_ONLY_TABLES);
		Map<String, Long> otherFeedbackBefore = rowCounts(OTHER_FEEDBACK_TABLES);
		Map<String, Long> outputBefore = rowCounts(BATCH_OUTPUT_TABLES);

		feedbackBatchService.runPreMarketBatch();

		assertThat(cardsForFixture()).isNotEmpty();
		assertThat(rowCounts(BATCH_OUTPUT_TABLES))
			.allSatisfy((table, after) -> assertThat(after).as("%s에 행이 생기지 않으면 이 단정이 헛돈다", table)
				.isGreaterThan(outputBefore.get(table)));
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
		assertThat(rowCounts(OTHER_FEEDBACK_TABLES)).isEqualTo(otherFeedbackBefore);
	}

	@Test
	@DisplayName("개장 전 배치가 PRE_MARKET·FULL 요약 2건과 브리핑 1건을 실제로 만든다")
	void createsBothSummaryScopesAndTheBriefing() {
		givenReadyReplaySession();

		feedbackBatchService.runPreMarketBatch();

		assertThat(summariesForFixture())
			.extracting(InstrumentNewsSummary::getScope)
			.containsExactlyInAnyOrder(NewsSummaryScope.PRE_MARKET, NewsSummaryScope.FULL);
		assertThat(marketBriefingRepository.findAll())
			.filteredOn(briefing -> ORIGIN_TRADE_DATE.equals(briefing.getOriginTradeDate()))
			.singleElement()
			.extracting(MarketBriefing::getMarket)
			.isEqualTo(Market.STOCK);
	}

	@Test
	@DisplayName("같은 서비스 날짜에 두 번 실행해도 요약·브리핑이 중복 생성되지 않는다")
	void doesNotDuplicateSummariesOrBriefingsWhenRunTwice() {
		givenReadyReplaySession();

		feedbackBatchService.runPreMarketBatch();
		List<Long> firstRunSummaryIds = summariesForFixture().stream().map(InstrumentNewsSummary::getId).sorted()
			.toList();
		long briefingsAfterFirstRun = marketBriefingRepository.count();
		assertThat(firstRunSummaryIds).hasSize(2);

		feedbackBatchService.runPreMarketBatch();

		assertThat(summariesForFixture().stream().map(InstrumentNewsSummary::getId).sorted().toList())
			.isEqualTo(firstRunSummaryIds);
		assertThat(marketBriefingRepository.count()).isEqualTo(briefingsAfterFirstRun);
	}

	@Test
	@DisplayName("고정 Clock 아래에서도 배치 소요 시간이 0이 아니게 찍히고 단계별 시간과 LLM 호출 수가 함께 남는다")
	void logsNonZeroElapsedTimeEvenUnderAFixedClock() {
		givenReadyReplaySession();

		List<String> logs = runBatchCapturingLogs();

		String completion = logs.stream()
			.filter(message -> message.startsWith("개장 전 배치를 마쳤다."))
			.findFirst()
			.orElseThrow(() -> new AssertionError("종료 로그가 없다. 남은 로그=" + logs));
		Matcher elapsed = Pattern.compile("소요=(\\d+)ms").matcher(completion);
		assertThat(elapsed.find()).as("종료 로그에 소요 시간이 없다: %s", completion).isTrue();
		assertThat(Long.parseLong(elapsed.group(1)))
			.as("고정 Clock으로 잰 구현이면 여기가 0이다: %s", completion)
			.isPositive();
		assertThat(completion).contains("LLM호출=0건");

		assertThat(logs)
			.filteredOn(message -> message.startsWith("개장 전 배치 단계를 마쳤다."))
			.extracting(message -> message.replaceAll(".*단계=(.+) 소요=.*", "$1"))
			.containsExactly("브리핑", "전장 요약", "탐지", "시가 갭 카드", "장중 카드", "종일 요약");
	}

	private List<String> runBatchCapturingLogs() {
		Logger logger = (Logger)LoggerFactory.getLogger(FeedbackBatchService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level originalLevel = logger.getLevel();
		logger.setLevel(Level.INFO);
		logger.addAppender(appender);
		try {
			feedbackBatchService.runPreMarketBatch();
			return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(originalLevel);
			appender.stop();
		}
	}

	private List<InstrumentNewsSummary> summariesForFixture() {
		return instrumentNewsSummaryRepository.findAll().stream()
			.filter(summary -> ORIGIN_TRADE_DATE.equals(summary.getOriginTradeDate()))
			.filter(summary -> summary.getInstrument().getId().equals(instrument.getId()))
			.toList();
	}

	private List<PriceMoveEvent> cardsForFixture() {
		return priceMoveEventRepository.findAll().stream()
			.filter(card -> ORIGIN_TRADE_DATE.equals(card.getOriginTradeDate()))
			.filter(card -> card.getInstrument().getId().equals(instrument.getId()))
			.toList();
	}

	private Map<String, Long> rowCounts(List<String> tables) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : tables) {
			counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		return counts;
	}

}
