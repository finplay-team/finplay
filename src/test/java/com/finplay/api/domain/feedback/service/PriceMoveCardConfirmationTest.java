package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, PriceMoveCardWriter.class})
class PriceMoveCardConfirmationTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 28);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 7, 27);

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 8, 45);

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private PriceMoveCardWriter priceMoveCardWriter;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final NarrativeService narrativeService = mock(NarrativeService.class);

	private Instrument instrument;

	private PriceMoveCardService service;

	@BeforeEach
	void setUp() {
		instrument = instrumentRepository.save(Instrument.create(
			Market.STOCK, "CARD001", "테스트종목A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		NewsMatcher newsMatcher = new NewsMatcher(
			marketNewsItemRepository,
			new FeedbackNewsProperties(
				"0 0/30 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, 5, 50, 30, 30),
			new FeedbackCryptoProperties(30, 6, 5, 24, 100, 35, 30),
			new BusinessDayCalendar());
		service = new PriceMoveCardService(
			priceMoveEventRepository,
			priceMoveCardWriter,
			newsMatcher,
			narrativeService,
			Clock.fixed(NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")));
		when(narrativeService.resolvePriceMoveNarrative(any()))
			.thenReturn(NarrativeResultDto.llm("반도체 업황 우려로 움직였습니다."));
	}

	private MarketNewsItem saveNews(String title, LocalDateTime publishedAt) {
		return marketNewsItemRepository.save(MarketNewsItem.create(
			instrument,
			MarketNewsItemType.NEWS,
			title,
			"테스트경제",
			"https://news.example.com/" + title,
			publishedAt,
			publishedAt.plusMinutes(30)));
	}

	private static PriceMoveDetectionDto intraday(LocalTime windowEnd) {
		return new PriceMoveDetectionDto(
			PriceMoveEventType.INTRADAY,
			windowEnd.minusMinutes(5),
			windowEnd,
			new BigDecimal("-0.018200"),
			new BigDecimal("3.2500"));
	}

	private static PriceMoveDetectionDto openingGap(LocalTime firstCandleTime) {
		return new PriceMoveDetectionDto(
			PriceMoveEventType.OPENING_GAP,
			firstCandleTime,
			firstCandleTime,
			new BigDecimal("0.030000"),
			new BigDecimal("3.0000"));
	}

	private Optional<PriceMoveEvent> confirm(PriceMoveDetectionDto detection) {
		return service.confirmStockCard(instrument, ORIGIN_TRADE_DATE, detection);
	}

	@Test
	@DisplayName("windowStart가 똑같이 09:00인 장중 첫 후보와 시가 갭 카드가 같은 날 함께 저장된다")
	void intradayFirstCandidateAndOpeningGapCardAreBothStoredForTheSameDayAndWindowStart() {
		saveNews("전장 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveNews("장중 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 2)));
		PriceMoveDetectionDto gap = openingGap(LocalTime.of(9, 0));
		PriceMoveDetectionDto firstIntraday = intraday(LocalTime.of(9, 5));
		assertThat(gap.windowStart()).isEqualTo(firstIntraday.windowStart()).isEqualTo(LocalTime.of(9, 0));

		assertThat(confirm(gap)).isPresent();
		assertThat(confirm(firstIntraday)).isPresent();

		List<PriceMoveEvent> stored = priceMoveEventRepository.findAll();
		assertThat(stored).hasSize(2);
		assertThat(stored).extracting(PriceMoveEvent::getWindowStart).containsOnly(LocalTime.of(9, 0));
		assertThat(stored).extracting(PriceMoveEvent::getEventType)
			.containsExactlyInAnyOrder(PriceMoveEventType.OPENING_GAP, PriceMoveEventType.INTRADAY);
	}

	@Test
	@DisplayName("중복 판정은 종류별로 갈린다 — 갭 카드가 있어도 같은 windowStart의 장중은 false다")
	void existsFinderDistinguishesEventTypeAtTheSameWindowStart() {
		saveNews("전장 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		assertThat(confirm(openingGap(LocalTime.of(9, 0)))).isPresent();

		assertThat(priceMoveEventRepository
			.existsByInstrumentIdAndOriginTradeDateAndEventTypeAndWindowStart(
				instrument.getId(), ORIGIN_TRADE_DATE, PriceMoveEventType.OPENING_GAP, LocalTime.of(9, 0)))
			.isTrue();
		assertThat(priceMoveEventRepository
			.existsByInstrumentIdAndOriginTradeDateAndEventTypeAndWindowStart(
				instrument.getId(), ORIGIN_TRADE_DATE, PriceMoveEventType.INTRADAY, LocalTime.of(9, 0)))
			.isFalse();
	}

	@Test
	@DisplayName("③ 첫 분봉이 09:03인 갭 카드의 reveal_time이 09:00으로 저장된다")
	void storesOpeningGapRevealTimeClampedToMarketOpen() {
		saveNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 40)));

		PriceMoveEvent card = confirm(openingGap(LocalTime.of(9, 3))).orElseThrow();

		assertThat(reloadRevealTime(card)).isEqualTo(LocalTime.of(9, 0));
		assertThat(reloadRevealTime(card))
			.isNotEqualTo(LocalTime.of(18, 40))
			.isNotEqualTo(LocalTime.of(9, 4));
	}

	@Test
	@DisplayName("④ 장중 카드의 reveal_time이 windowEnd + 1분으로 저장된다")
	void storesIntradayRevealTimeWithTheOneMinuteOffset() {
		saveNews("장중 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)));

		PriceMoveEvent card = confirm(intraday(LocalTime.of(11, 25))).orElseThrow();

		assertThat(reloadRevealTime(card)).isEqualTo(LocalTime.of(11, 26));
		assertThat(reloadRevealTime(card)).isNotEqualTo(LocalTime.of(11, 25));
	}

	@Test
	@DisplayName("⑤ 근거가 windowEnd보다 늦으면 reveal_time이 그 발행시각으로 밀려 저장된다")
	void storesIntradayRevealTimePushedByTheLatestSource() {
		saveNews("이른 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)));
		saveNews("늦은 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 29)));

		PriceMoveEvent card = confirm(intraday(LocalTime.of(11, 25))).orElseThrow();

		assertThat(reloadRevealTime(card)).isEqualTo(LocalTime.of(11, 29));
		assertThat(reloadRevealTime(card)).isNotEqualTo(LocalTime.of(11, 26));
	}

	@Test
	@DisplayName("reveal_time은 날짜 없이 TIME 값으로 저장된다")
	void storesRevealTimeAsATimeValueWithoutADate() {
		saveNews("장중 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)));
		confirm(intraday(LocalTime.of(11, 25)));

		List<String> revealTimes = jdbcTemplate.queryForList("select reveal_time from price_move_events", String.class);

		assertThat(revealTimes).containsExactly("11:26:00");
	}

	@Test
	@DisplayName("같은 인자로 두 번 확정하면 두 번째는 empty()이고 행은 1건, 첫 서술이 그대로 남는다")
	void secondConfirmationOfTheSameCardIsANoOp() {
		saveNews("장중 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)));
		PriceMoveDetectionDto detection = intraday(LocalTime.of(11, 25));
		PriceMoveEvent first = confirm(detection).orElseThrow();

		when(narrativeService.resolvePriceMoveNarrative(any()))
			.thenReturn(NarrativeResultDto.template("덮어쓰면 안 되는 문장"));

		assertThat(confirm(detection)).isEmpty();
		assertThat(priceMoveEventRepository.findAll()).hasSize(1);
		PriceMoveEvent stored = priceMoveEventRepository.findById(first.getId()).orElseThrow();
		assertThat(stored.getNarrative()).isEqualTo("반도체 업황 우려로 움직였습니다.");
		assertThat(stored.getNarrativeSource()).isEqualTo(NarrativeSource.LLM);
		verify(narrativeService, times(1)).resolvePriceMoveNarrative(any());
	}

	@Test
	@DisplayName("근거 연결이 카드와 함께 저장되고 개수는 NewsMatcher가 준 만큼이다")
	void storesOneSourceRowPerMatchedArticle() {
		saveNews("기사1", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)));
		saveNews("기사2", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 24)));
		saveNews("근거창 밖 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 0)));

		PriceMoveEvent card = confirm(intraday(LocalTime.of(11, 25))).orElseThrow();

		assertThat(priceMoveEventSourceRepository.findAll()).hasSize(2);
		List<Long> linkedEventIds = jdbcTemplate.queryForList(
			"select price_move_event_id from price_move_event_sources", Long.class);
		assertThat(linkedEventIds).containsOnly(card.getId());
	}

	private LocalTime reloadRevealTime(PriceMoveEvent card) {
		return priceMoveEventRepository.findById(card.getId()).orElseThrow().getRevealTime();
	}
}
