// 고정 Clock(08:45) + Testcontainers로 개장 전 배치 종단을 검증한다 — 카드 생성, 중복 없음, 원장 불변.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.StockReplayService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import({TestcontainersConfiguration.class, FeedbackBatchIntegrationTest.FixedClockTestConfig.class})
class FeedbackBatchIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

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
	private JdbcTemplate jdbcTemplate;

	private Instrument instrument;

	@BeforeEach
	void setUp() {
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

	// 8개 이슈 공통 조건 — 쓰기가 price_move_events·price_move_event_sources 밖으로 나가지 않는다.
	@Test
	@DisplayName("배치 실행 전후로 원장 테이블과 읽기 전용 테이블의 행이 변하지 않는다")
	void neverWritesOutsideTheTwoCardTables() {
		givenReadyReplaySession();
		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		Map<String, Long> readOnlyBefore = rowCounts(READ_ONLY_TABLES);

		feedbackBatchService.runPreMarketBatch();

		// 배치가 실제로 쓰기를 했는데도 나머지가 그대로여야 의미가 있다.
		assertThat(cardsForFixture()).isNotEmpty();
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
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

	@TestConfiguration
	static class FixedClockTestConfig {

		// 08:45 — 재생세션 확정(08:40) 직후이자 개장(09:00) 전. getRevealedCandles가 빈 목록인 시각이다.
		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(BATCH_AT.atZone(KST).toInstant(), KST);
		}
	}
}
