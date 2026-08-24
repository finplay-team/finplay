// 고정 Clock(08:45) + Testcontainers로 개장 전 배치 종단을 검증한다 — 카드 생성, 중복 없음, 원장 불변.
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

// 완료 조건 배치 ①·⑤와 8개 이슈 공통 조건인 원장 불변이 이 파일의 목표다. ②의 크론 절반은
// FeedbackBatchScheduleTest가, ②의 READY 절반과 ③④는 FeedbackBatchServiceTest가 맡는다.
//
// LLM은 부르지 않는다 — application.yml의 api-key가 `not-configured`라 OpenAiNarrativeGenerator가
// 호출 전에 실패를 반환하고 NarrativeService가 §템플릿 문장으로 폴백한다 (PRD C-005).
//
// 공유 컨테이너를 더럽히지 않도록 클래스 트랜잭션으로 감싼다 (NewsCollectionIntegrationTest 선례).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class FeedbackBatchIntegrationTest {

	// 서비스 날짜 2026-08-06(목)의 08:45에 배치가 돈다 — 이 시각이 이 파일의 핵심이다.
	private static final LocalDateTime BATCH_AT = LocalDateTime.of(2026, 8, 6, 8, 45);

	// 원본 거래일 2026-08-05(수)과 그 직전 영업일 2026-08-04(화)
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	private static final String STOCK_SYMBOL = "005930";

	// 09:00~09:10 분봉. 09:06→09:10에 큰 상승이 있어 09:05~09:10 구간이 장중 후보가 된다(항목 1의 픽스처).
	private static final long[] CLOSES = {
		10000, 10002, 10004, 10006, 10008, 10010, 10012, 10120, 10230, 10340, 10450};

	// 첫 분봉 시가. 직전 거래일 마지막 분봉 종가(9700) 대비 약 +3.09%라 시가 갭 임계치 1%를 넘는다.
	private static final BigDecimal FIRST_CANDLE_OPEN = new BigDecimal("10000");
	private static final BigDecimal PREVIOUS_CLOSE = new BigDecimal("9700");

	// 배치 실행 전후로 행이 변하면 안 되는 원장 테이블 (주문·체결·계좌/잔액·보유·손익 배분)
	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	// 이 배치가 읽기만 해야 하는 테이블
	private static final List<String> READ_ONLY_TABLES = List.of("instruments", "stock_candles", "market_news_items",
		"stock_replay_sessions");

	// 이 배치가 쓰는 것이 정당한 네 테이블 — 카드 2종 + 요약·브리핑. 요약·브리핑이 붙으면서 둘에서 넷이 됐다.
	private static final List<String> BATCH_OUTPUT_TABLES = List.of("price_move_events", "price_move_event_sources",
		"instrument_news_summaries", "market_briefings");

	// 같은 feedback 도메인이지만 이 배치의 산출물이 아닌 테이블 — 매도 회고와 집단 비교는 다른 이슈 소유다.
	// 원장만 보면 "쓰기가 네 테이블 밖으로 나가지 않는다"의 절반만 확인된다.
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

	// 전역 Clock 빈을 대신하는 공용 테스트 시계 (TestClockConfig). 기준 시각은 @BeforeEach에서 세운다.
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

		// 재생세션은 테스트마다 상태가 달라 여기서 만들지 않는다 — service_date가 유니크라 여기서 만들고
		// 뒤에서 지웠다 다시 넣으면 flush 시점 때문에 중복키가 난다.

		// 직전 거래일 마지막 분봉 — 15:30이 아닌 시각에 둔다 (§C-2-1).
		saveCandle(PREVIOUS_TRADE_DATE, LocalTime.of(15, 27), PREVIOUS_CLOSE, PREVIOUS_CLOSE);
		for (int minute = 0; minute < CLOSES.length; minute++) {
			BigDecimal close = BigDecimal.valueOf(CLOSES[minute]);
			BigDecimal open = minute == 0 ? FIRST_CANDLE_OPEN : close;
			saveCandle(ORIGIN_TRADE_DATE, LocalTime.of(9, 0).plusMinutes(minute), open, close);
		}

		// 시가 갭 카드의 근거 — 전장 구간 [D-1 15:30, D 09:00] 안
		saveNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		// 장중 카드의 근거 — windowEnd 09:10의 근거창 [08:40, 09:15] 안
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

	// 배치 ① — 0건이면서 예외가 없는 상태를 통과로 읽지 않는다. 같은 시각에 getRevealedCandles가 실제로
	// 빈 목록임을 함께 단정해, 카드가 "그 경로로는 볼 수 없는 데이터"에서 나왔음을 못박는다.
	@Test
	@DisplayName("개장 전 08:45에 배치가 하루치 분봉을 받아 카드를 실제로 생성한다")
	void createsCardsFromFullDayCandlesAtPreMarketTime() {
		givenReadyReplaySession();
		// 회귀 구현이 쓰는 경로는 이 시각에 아무것도 주지 않는다 — 그래서 0건이 되어도 예외가 나지 않는다.
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
		// 근거가 실제로 연결됐다 — 근거 0건 카드는 존재하지 않아야 한다(FEED-003).
		assertThat(priceMoveEventSourceRepository.count()).isGreaterThanOrEqualTo(cards.size());
	}

	// 배치 ⑤ — 유니크 제약 + 선판정이 실제로 작동하는지를 종단에서 본다.
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

	// 배치 ② 뒷면의 종단 확인 — 세션이 READY가 아니면 행이 하나도 생기지 않고 예외도 없다.
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

	// 8개 이슈 공통 조건(원장 불변) — 쓰기가 카드 2종 + 요약·브리핑 네 테이블 밖으로 나가지 않는다.
	// 요약·브리핑이 붙으면서 정당한 쓰기 대상이 둘에서 넷이 됐고, 그만큼 "밖"의 범위도 넓혀 확인한다.
	@Test
	@DisplayName("배치가 네 산출물 테이블에만 쓰고 원장·읽기 전용·다른 피드백 테이블은 그대로다")
	void neverWritesOutsideTheFourBatchOutputTables() {
		givenReadyReplaySession();
		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		Map<String, Long> readOnlyBefore = rowCounts(READ_ONLY_TABLES);
		Map<String, Long> otherFeedbackBefore = rowCounts(OTHER_FEEDBACK_TABLES);
		Map<String, Long> outputBefore = rowCounts(BATCH_OUTPUT_TABLES);

		feedbackBatchService.runPreMarketBatch();

		// 배치가 실제로 쓰기를 했는데도 나머지가 그대로여야 의미가 있다 — 네 테이블 전부가 늘어야 한다.
		assertThat(cardsForFixture()).isNotEmpty();
		assertThat(rowCounts(BATCH_OUTPUT_TABLES))
			.allSatisfy((table, after) -> assertThat(after).as("%s에 행이 생기지 않으면 이 단정이 헛돈다", table)
				.isGreaterThan(outputBefore.get(table)));
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
		assertThat(rowCounts(OTHER_FEEDBACK_TABLES)).isEqualTo(otherFeedbackBefore);
	}

	// 요약·브리핑이 실제로 생기는지를 종단에서 본다. 단위 테스트는 mock 리포지터리에 save가 갔는지까지만 보므로
	// 구간 질의가 실제 MySQL에서 0건을 주는 형태여도 초록이다.
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

	// 배치 ⑤의 요약·브리핑 절반 — 선판정과 유니크가 종단에서 실제로 작동하는지 본다.
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

	// 이슈 #198 — 이 파일이 고정 Clock(08:45)을 쓰기 때문에 여기가 회귀를 잡는 자리다. 소요 시간을 Clock으로
	// 재면 시작·종료 시각이 같아 항상 0ms가 찍히는데, 그래도 다른 단정은 전부 통과해 아무도 눈치채지 못한다.
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
		// 배치 1회분의 실제 호출 수 — 이 실행은 키가 not-configured라 호출 자체가 나가지 않아 0건이 정답이다.
		assertThat(completion).contains("LLM호출=0건");

		// 어느 단계에서 시간이 가는지는 총 시간이 아니라 이 여섯 줄에만 있다.
		assertThat(logs)
			.filteredOn(message -> message.startsWith("개장 전 배치 단계를 마쳤다."))
			.extracting(message -> message.replaceAll(".*단계=(.+) 소요=.*", "$1"))
			.containsExactly("브리핑", "전장 요약", "탐지", "시가 갭 카드", "장중 카드", "종일 요약");
	}

	// 소요 시간의 유일한 외부 관찰점이 로그라 배치 로거에 임시 appender를 붙인다 (DartDisclosureCollectorTest 선례).
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
