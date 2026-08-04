// 실제 MySQL에 카드를 확정 저장해 유니크 축(탐지 ⑦)과 저장된 reveal_time(게이트 ③④⑤)을 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.service.BusinessDayCalendar;
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

// PriceMoveCardServiceTest는 writer가 mock이라 "무엇을 넘겼는가"까지만 볼 수 있다. 유니크 축이 실제로
// event_type을 포함하는지, 저장된 reveal_time이 TIME 컬럼에 그 값으로 남는지는 실 MySQL에서만 드러난다.
//
// NewsMatcher와 PriceMoveCardService는 슬라이스가 올리지 않으므로 직접 생성한다 — 리포지토리 3종은 실
// 컨테이너에 붙은 진짜 빈이고, PriceMoveCardWriter는 @Import로 올려 실제 프록시를 쓴다. 외부 호출인
// NarrativeService만 mock이다(PRD C-005 — 실제 LLM을 부르는 테스트를 만들지 않는다).
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, PriceMoveCardWriter.class})
class PriceMoveCardConfirmationTest {

	// 2026-07-28(화). 직전 거래일은 바로 전날 2026-07-27(월)이다.
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
		// V7 시드(005930 등)와 겹치지 않는 테스트 전용 심볼 — UNIQUE(symbol) 충돌 방지.
		instrument = instrumentRepository.save(Instrument.create(
			Market.STOCK, "CARD001", "테스트종목A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		NewsMatcher newsMatcher = new NewsMatcher(
			marketNewsItemRepository,
			// §C-7 기본값
			new FeedbackNewsProperties("0 0/30 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, 5),
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

	// --- 탐지 ⑦ 유니크 축 ---

	// W=5라 장중 첫 후보의 windowStart는 09:00이고, 첫 분봉이 09:00인 날 갭 카드의 windowStart도 09:00이다.
	// 두 값을 정확히 같게 잡아야 축 검증이 성립한다 — 다르게 잡으면 event_type이 유니크에 없어도 통과한다.
	@Test
	@DisplayName("windowStart가 똑같이 09:00인 장중 첫 후보와 시가 갭 카드가 같은 날 함께 저장된다")
	void intradayFirstCandidateAndOpeningGapCardAreBothStoredForTheSameDayAndWindowStart() {
		saveNews("전장 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveNews("장중 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 2)));
		PriceMoveDetectionDto gap = openingGap(LocalTime.of(9, 0));
		PriceMoveDetectionDto firstIntraday = intraday(LocalTime.of(9, 5));
		// 픽스처 전제 — 두 카드의 windowStart가 실제로 같은 값이어야 이 테스트가 축을 검증한다.
		assertThat(gap.windowStart()).isEqualTo(firstIntraday.windowStart()).isEqualTo(LocalTime.of(9, 0));

		assertThat(confirm(gap)).isPresent();
		assertThat(confirm(firstIntraday)).isPresent();

		List<PriceMoveEvent> stored = priceMoveEventRepository.findAll();
		assertThat(stored).hasSize(2);
		assertThat(stored).extracting(PriceMoveEvent::getWindowStart).containsOnly(LocalTime.of(9, 0));
		assertThat(stored).extracting(PriceMoveEvent::getEventType)
			.containsExactlyInAnyOrder(PriceMoveEventType.OPENING_GAP, PriceMoveEventType.INTRADAY);
	}

	// 파인더에서 eventType을 빼면 갭 카드 하나 때문에 장중 첫 후보가 "이미 있다"로 판정되어 조용히 사라진다.
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

	// --- 게이트 ③④⑤ — 저장된 값 ---

	@Test
	@DisplayName("③ 첫 분봉이 09:03인 갭 카드의 reveal_time이 09:00으로 저장된다")
	void storesOpeningGapRevealTimeClampedToMarketOpen() {
		saveNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 40)));

		PriceMoveEvent card = confirm(openingGap(LocalTime.of(9, 3))).orElseThrow();

		assertThat(reloadRevealTime(card)).isEqualTo(LocalTime.of(9, 0));
		// 클램프가 없으면 18:40, +1분을 더하면 09:04다 — 세 값이 모두 다른 픽스처다.
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

	// reveal_time은 TIME 컬럼이라 날짜가 붙지 않는다 (§노출 판정 — 재재생 때문에 절대 시각으로 저장하지 않는다).
	@Test
	@DisplayName("reveal_time은 날짜 없이 TIME 값으로 저장된다")
	void storesRevealTimeAsATimeValueWithoutADate() {
		saveNews("장중 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)));
		confirm(intraday(LocalTime.of(11, 25)));

		List<String> revealTimes = jdbcTemplate.queryForList("select reveal_time from price_move_events", String.class);

		assertThat(revealTimes).containsExactly("11:26:00");
	}

	// --- 중복 실행 (§실패 처리) ---

	@Test
	@DisplayName("같은 인자로 두 번 확정하면 두 번째는 empty()이고 행은 1건, 첫 서술이 그대로 남는다")
	void secondConfirmationOfTheSameCardIsANoOp() {
		saveNews("장중 기사", LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)));
		PriceMoveDetectionDto detection = intraday(LocalTime.of(11, 25));
		PriceMoveEvent first = confirm(detection).orElseThrow();

		// 두 번째 실행에서 LLM이 다른 문장을 주더라도 카드가 덮어써지지 않아야 한다.
		when(narrativeService.resolvePriceMoveNarrative(any()))
			.thenReturn(NarrativeResultDto.template("덮어쓰면 안 되는 문장"));

		assertThat(confirm(detection)).isEmpty();
		assertThat(priceMoveEventRepository.findAll()).hasSize(1);
		PriceMoveEvent stored = priceMoveEventRepository.findById(first.getId()).orElseThrow();
		assertThat(stored.getNarrative()).isEqualTo("반도체 업황 우려로 움직였습니다.");
		assertThat(stored.getNarrativeSource()).isEqualTo(NarrativeSource.LLM);
		// 두 번째 호출에서는 서술 생성 자체가 없어야 한다 — 두 번 확정했지만 LLM 경로는 첫 번째 1회뿐이다.
		// (스텁을 다시 걸어 둔 "덮어쓰면 안 되는 문장"이 쓰이지 않았다는 것이 위 단정과 짝이다.)
		verify(narrativeService, times(1)).resolvePriceMoveNarrative(any());
	}

	// --- 근거 연결 ---

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
