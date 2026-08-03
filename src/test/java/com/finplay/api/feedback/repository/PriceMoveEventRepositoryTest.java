// 실제 MySQL에서 price_move_events의 주식·코인 두 형태 매핑과 UNIQUE(instrument_id, origin_trade_date, event_type, window_start)를 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.feedback.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventType;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class PriceMoveEventRepositoryTest {

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Instrument stock;
	private Instrument crypto;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 3);
	private static final LocalTime WINDOW_START = LocalTime.of(9, 0);
	private static final LocalTime WINDOW_END = LocalTime.of(9, 5);
	private static final LocalTime REVEAL_TIME = LocalTime.of(9, 20);
	// 코인 자정 넘김 케이스 — 구간이 23:58 ~ 00:03이라 TIME으로는 시작이 끝보다 늦다 (§C-9).
	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 8, 4, 0, 3, 0);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 4, 0, 3, 30);
	private static final BigDecimal CHANGE_RATE = new BigDecimal("0.052500");
	private static final BigDecimal DETECTION_SCORE = new BigDecimal("3.2500");

	@BeforeEach
	void setUp() {
		// V7 시드(005930 등)와 겹치지 않는 테스트 전용 심볼을 사용한다 — UNIQUE(symbol) 충돌 방지.
		stock = instrumentRepository.save(Instrument.create(
			Market.STOCK, "MOVE001", "테스트종목A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		crypto = instrumentRepository.save(Instrument.create(
			Market.CRYPTO, "MOVEBTC", "테스트코인", new BigDecimal("1"), 5000, true, LocalDateTime.now()));
	}

	private PriceMoveEvent newStockEvent(PriceMoveEventType eventType, LocalTime windowStart) {
		return PriceMoveEvent.createStock(
			stock,
			eventType,
			ORIGIN_TRADE_DATE,
			windowStart,
			WINDOW_END,
			CHANGE_RATE,
			DETECTION_SCORE,
			"반도체 업황 우려로 하락했습니다.",
			NarrativeSource.LLM,
			REVEAL_TIME,
			NOW);
	}

	private PriceMoveEvent newCryptoEvent(LocalDateTime occurredAt) {
		return PriceMoveEvent.createCrypto(
			crypto,
			ORIGIN_TRADE_DATE,
			occurredAt,
			CHANGE_RATE,
			DETECTION_SCORE,
			"대형 거래소 상장 소식이 있었습니다.",
			NarrativeSource.TEMPLATE,
			NOW);
	}

	// --- 검증 ① 주식 형태와 코인 형태가 둘 다 저장되고 다시 읽었을 때 값이 같다 ---

	@Test
	@DisplayName("주식 형태 행은 TIME 두 개를 채우고 occurred_at이 NULL인 채로 복원된다")
	void stockShapedEventRoundTripsWithWindowTimesAndNullOccurredAt() {
		Long id = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, WINDOW_START)).getId();

		PriceMoveEvent found = priceMoveEventRepository.findById(id).orElseThrow();

		assertThat(found.getInstrument().getId()).isEqualTo(stock.getId());
		assertThat(found.getMarket()).isEqualTo(Market.STOCK);
		assertThat(found.getEventType()).isEqualTo(PriceMoveEventType.INTRADAY);
		assertThat(found.getOriginTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
		assertThat(found.getWindowStart()).isEqualTo(WINDOW_START);
		assertThat(found.getWindowEnd()).isEqualTo(WINDOW_END);
		assertThat(found.getOccurredAt()).isNull();
		assertThat(found.getRevealTime()).isEqualTo(REVEAL_TIME);
		assertThat(found.getChangeRate()).isEqualByComparingTo(CHANGE_RATE);
		assertThat(found.getDetectionScore()).isEqualByComparingTo(DETECTION_SCORE);
		assertThat(found.getNarrativeSource()).isEqualTo(NarrativeSource.LLM);
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("코인 형태 행은 자정을 넘긴 occurred_at을 그대로 복원하고 TIME 컬럼이 전부 NULL이다")
	void cryptoShapedEventRoundTripsWithAbsoluteTimestampAndNullTimeColumns() {
		Long id = priceMoveEventRepository.saveAndFlush(newCryptoEvent(OCCURRED_AT)).getId();

		PriceMoveEvent found = priceMoveEventRepository.findById(id).orElseThrow();

		assertThat(found.getInstrument().getId()).isEqualTo(crypto.getId());
		assertThat(found.getMarket()).isEqualTo(Market.CRYPTO);
		// 날짜까지 함께 복원돼야 자정을 넘긴 구간(23:58 ~ 00:03)이 어긋나지 않는다 (§C-9).
		assertThat(found.getOccurredAt()).isEqualTo(OCCURRED_AT);
		assertThat(found.getOccurredAt().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 4));
		assertThat(found.getWindowStart()).isNull();
		assertThat(found.getWindowEnd()).isNull();
		// 코인은 실시간이라 스포일러가 성립하지 않아 reveal_time이 없다 (FEED-005).
		assertThat(found.getRevealTime()).isNull();
		assertThat(found.getOriginTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
	}

	// --- 팩토리가 §C-9의 형태 불변식을 지키는지 (DB가 막을 수 없는 부분이다) ---

	@Test
	@DisplayName("createStock은 market=STOCK과 occurred_at NULL을 강제한다 — 호출자가 코인 형태를 섞을 수 없다")
	void createStockAlwaysStoresStockMarketAndLeavesOccurredAtNull() {
		priceMoveEventRepository.saveAndFlush(newStockEvent(PriceMoveEventType.OPENING_GAP, WINDOW_START));

		Map<String, Object> row = jdbcTemplate.queryForMap(
			"select market, window_start, window_end, occurred_at from price_move_events");

		assertThat(row.get("market")).isEqualTo("STOCK");
		assertThat(row.get("window_start")).isNotNull();
		assertThat(row.get("window_end")).isNotNull();
		assertThat(row.get("occurred_at")).isNull();
	}

	@Test
	@DisplayName("createCrypto는 market=CRYPTO·event_type=INTRADAY와 TIME 컬럼 NULL을 강제한다")
	void createCryptoAlwaysStoresCryptoMarketIntradayTypeAndNullTimeColumns() {
		priceMoveEventRepository.saveAndFlush(newCryptoEvent(OCCURRED_AT));

		Map<String, Object> row = jdbcTemplate.queryForMap(
			"select market, event_type, window_start, window_end, occurred_at, reveal_time from price_move_events");

		assertThat(row.get("market")).isEqualTo("CRYPTO");
		// 코인은 개장이 없어 시가 갭 카드가 성립하지 않는다 — 팩토리가 INTRADAY로 고정한다.
		assertThat(row.get("event_type")).isEqualTo("INTRADAY");
		assertThat(row.get("window_start")).isNull();
		assertThat(row.get("window_end")).isNull();
		assertThat(row.get("reveal_time")).isNull();
		assertThat(row.get("occurred_at")).isNotNull();
	}

	@Test
	@DisplayName("enum 3종이 전부 이름 문자열로 저장된다")
	void enumColumnsStoreTheirNamesAsStrings() {
		// ORDINAL로 매핑되면 VARCHAR(20)에 "0"·"1"이 들어가도 MySQL은 조용히 받는다. 실제 저장 문자열을 확인한다.
		priceMoveEventRepository.saveAndFlush(newStockEvent(PriceMoveEventType.OPENING_GAP, WINDOW_START));

		Map<String, Object> row = jdbcTemplate.queryForMap(
			"select market, event_type, narrative_source from price_move_events");

		assertThat(row.get("market")).isEqualTo("STOCK");
		assertThat(row.get("event_type")).isEqualTo("OPENING_GAP");
		assertThat(row.get("narrative_source")).isEqualTo("LLM");
	}

	@Test
	@DisplayName("narrative는 varchar(255)를 넘는 서술도 잘리지 않고 그대로 복원된다")
	void narrativeColumnKeepsTextLongerThanTwoHundredFiftyFiveCharacters() {
		// 기본 매핑(varchar(255))이면 ddl-auto=validate가 막지만, 실제 긴 서술의 왕복까지 확인한다.
		String longNarrative = "급락 구간입니다. ".repeat(60);
		assertThat(longNarrative.length()).isGreaterThan(255);
		PriceMoveEvent event = PriceMoveEvent.createStock(
			stock,
			PriceMoveEventType.INTRADAY,
			ORIGIN_TRADE_DATE,
			WINDOW_START,
			WINDOW_END,
			CHANGE_RATE,
			DETECTION_SCORE,
			longNarrative,
			NarrativeSource.LLM,
			REVEAL_TIME,
			NOW);

		Long id = priceMoveEventRepository.saveAndFlush(event).getId();

		assertThat(priceMoveEventRepository.findById(id).orElseThrow().getNarrative()).isEqualTo(longNarrative);
	}

	// --- 유니크 (instrument_id, origin_trade_date, event_type, window_start) ---

	@Test
	@DisplayName("window_start가 09:00으로 같아도 event_type이 다르면 장중 카드와 시가 갭 카드가 공존한다")
	void intradayAndOpeningGapCardsCoexistAtTheSameWindowStart() {
		// 장중 루프의 첫 후보(t=09:05, windowStart=09:00)와 시가 갭 카드의 windowStart가 겹친다.
		// event_type이 유니크에 없었다면 나중 삽입이 조용히 사라진다 (V13 주석).
		priceMoveEventRepository.saveAndFlush(newStockEvent(PriceMoveEventType.INTRADAY, WINDOW_START));
		priceMoveEventRepository.saveAndFlush(newStockEvent(PriceMoveEventType.OPENING_GAP, WINDOW_START));

		List<PriceMoveEvent> all = priceMoveEventRepository.findAll();

		assertThat(all).hasSize(2);
		assertThat(all).extracting(PriceMoveEvent::getEventType)
			.containsExactlyInAnyOrder(PriceMoveEventType.INTRADAY, PriceMoveEventType.OPENING_GAP);
	}

	@Test
	@DisplayName("같은 종목·거래일·event_type·window_start 2건째는 유니크 제약에 걸린다")
	void databaseRejectsDuplicateInstrumentDateEventTypeAndWindowStart() {
		priceMoveEventRepository.saveAndFlush(newStockEvent(PriceMoveEventType.INTRADAY, WINDOW_START));

		PriceMoveEvent duplicate = newStockEvent(PriceMoveEventType.INTRADAY, WINDOW_START);

		assertThatThrownBy(() -> priceMoveEventRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("window_start가 다르면 같은 거래일에 장중 카드 여러 건이 공존한다")
	void intradayCardsWithDifferentWindowStartsCoexistOnTheSameTradeDate() {
		priceMoveEventRepository.saveAndFlush(newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(9, 0)));
		priceMoveEventRepository.saveAndFlush(newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(10, 0)));

		assertThat(priceMoveEventRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("코인 카드는 window_start가 NULL이라 유니크가 중복을 막지 못한다 — 쿨다운·일일 상한이 그 역할을 한다")
	void cryptoCardsAreNotDeduplicatedByTheUniqueConstraintBecauseWindowStartIsNull() {
		// §C-9가 명시한 동작이다. MySQL 유니크는 NULL을 서로 다른 값으로 취급하므로 같은 종목·거래일·INTRADAY
		// 2건이 그대로 들어간다. 이 테스트는 결함이 아니라 "중복 방지를 유니크에 기대면 안 된다"는 근거다.
		priceMoveEventRepository.saveAndFlush(newCryptoEvent(OCCURRED_AT));
		priceMoveEventRepository.saveAndFlush(newCryptoEvent(OCCURRED_AT.plusMinutes(1)));

		assertThat(priceMoveEventRepository.count()).isEqualTo(2);
	}
}
