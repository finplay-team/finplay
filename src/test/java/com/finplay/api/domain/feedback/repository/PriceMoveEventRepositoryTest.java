// 실제 MySQL에서 price_move_events의 주식·코인 두 형태 매핑과 UNIQUE(instrument_id, origin_trade_date, event_type, window_start)를 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.domain.feedback.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
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

	// 보유 구간 파인더용 — windowEnd·revealTime을 케이스마다 따로 줘야 구간 좁히기와 게이트를 가른다.
	private PriceMoveEvent newStockEvent(
		PriceMoveEventType eventType, LocalTime windowStart, LocalTime windowEnd, LocalTime revealTime) {
		return PriceMoveEvent.createStock(
			stock,
			eventType,
			ORIGIN_TRADE_DATE,
			windowStart,
			windowEnd,
			CHANGE_RATE,
			DETECTION_SCORE,
			windowStart + " 카드",
			NarrativeSource.LLM,
			revealTime,
			NOW);
	}

	private PriceMoveEvent newCryptoEvent(LocalDateTime occurredAt) {
		// origin_trade_date를 넘기지 않는다 — 팩토리가 occurredAt의 날짜로 파생한다 (§C-9).
		return PriceMoveEvent.createCrypto(
			crypto,
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
		// origin_trade_date는 occurred_at의 날짜다 — 자정을 넘겼으므로 구간이 시작된 8/3이 아니라 8/4다 (§C-9).
		// 이 컬럼이 코인 일일 상한의 카운트 기준이라, 8/3으로 남으면 자정 직후 카드가 전날 몫으로 세어진다.
		assertThat(found.getOriginTradeDate()).isEqualTo(LocalDate.of(2026, 8, 4));
		assertThat(found.getOriginTradeDate()).isNotEqualTo(ORIGIN_TRADE_DATE);
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
	@DisplayName("createStock에 코인 종목을 넘기면 예외로 막힌다 — market 컬럼과 종목의 시장이 어긋난 카드는 만들 수 없다")
	void createStockRejectsACryptoInstrument() {
		// market은 instrument.market의 비정규화 사본이라 어긋나도 FK·유니크·validate 어디에도 걸리지 않는다.
		// 팩토리가 막지 않으면 코인 종목의 주식 형태 카드가 예외 없이 저장되고, 노출 게이트(주식만 reveal_time을
		// 본다)와 조회 범위(코인만 최근 24시간이다)가 서로 다른 시장을 가리켜 카드가 조용히 사라진다.
		assertThatThrownBy(() -> PriceMoveEvent.createStock(
			crypto,
			PriceMoveEventType.INTRADAY,
			ORIGIN_TRADE_DATE,
			WINDOW_START,
			WINDOW_END,
			CHANGE_RATE,
			DETECTION_SCORE,
			"반도체 업황 우려로 하락했습니다.",
			NarrativeSource.LLM,
			REVEAL_TIME,
			NOW))
			.isInstanceOf(IllegalArgumentException.class)
			// 두 단어가 들어 있는지만 보면 방향이 고정되지 않는다 — requireMarket이 종목의 시장과 카드 형태를
			// 뒤바꿔 조립해도 그대로 통과한다. 어느 쪽이 어느 자리에 들어가는지까지 완전 일치로 못 박는다.
			.hasMessage("종목의 시장(CRYPTO)이 카드 형태(STOCK)와 다릅니다.");

		assertThat(priceMoveEventRepository.count()).isZero();
	}

	@Test
	@DisplayName("createCrypto에 주식 종목을 넘기면 예외로 막힌다")
	void createCryptoRejectsAStockInstrument() {
		assertThatThrownBy(() -> PriceMoveEvent.createCrypto(
			stock,
			OCCURRED_AT,
			CHANGE_RATE,
			DETECTION_SCORE,
			"대형 거래소 상장 소식이 있었습니다.",
			NarrativeSource.TEMPLATE,
			NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("종목의 시장(STOCK)이 카드 형태(CRYPTO)와 다릅니다.");

		assertThat(priceMoveEventRepository.count()).isZero();
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

	// --- 매도 회고의 보유 구간 카드 파인더 (spec FEED-007, 이슈 #208 2번 항목) ---
	//
	// 보유 구간은 09:30 매수 ~ 14:40 매도로 고정하고 케이스마다 카드의 windowEnd·revealTime만 바꾼다.
	// 상한(revealTime)은 호출부가 그 체결의 서비스 날짜로 계산해 넘긴다 — 이 파인더는 받은 값만 적용한다.

	private static final LocalTime HOLD_FROM = LocalTime.of(9, 30);
	private static final LocalTime HOLD_TO = LocalTime.of(14, 40);

	// "그날 끝" 상한. LocalTime.MAX를 쓰면 안 되는 이유는 아래 전용 테스트에 있다.
	private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

	private List<PriceMoveEvent> findHeldCards(LocalTime revealCutoff) {
		return priceMoveEventRepository
			.findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				stock.getId(), ORIGIN_TRADE_DATE, HOLD_FROM, HOLD_TO, revealCutoff);
	}

	// windowStart로 좁힌 구현이면 두 단정이 동시에 깨진다 — 09:25 카드가 빠지고 14:35 카드가 들어온다.
	// 후자는 매도 뒤에 끝난 카드라 minutesBeforeSell이 −5가 되고, 3번 항목의 반사실 기준 카드로도 못 쓴다.
	@Test
	@DisplayName("보유 구간 파인더는 window_start가 아니라 window_end로 좁힌다")
	void heldPeriodFinderNarrowsByWindowEndNotWindowStart() {
		// 매수 전에 시작해 보유 구간 안에서 끝난 카드 — 포함된다.
		PriceMoveEvent startedBeforeBuy = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(9, 25), LocalTime.of(9, 31), LocalTime.MIN));
		// 보유 구간 안에서 시작해 매도 뒤에 끝난 카드 — 빠진다.
		priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(14, 35), LocalTime.of(14, 45), LocalTime.MIN));
		PriceMoveEvent inside = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(11, 20), LocalTime.of(11, 25), LocalTime.MIN));

		assertThat(findHeldCards(END_OF_DAY))
			.extracting(PriceMoveEvent::getId)
			.containsExactly(startedBeforeBuy.getId(), inside.getId());
	}

	// BETWEEN이 양 끝을 포함한다 — 부등호가 하나라도 배타로 바뀌면 매수·매도 분에 끝난 카드가 조용히 사라진다.
	@Test
	@DisplayName("window_end가 매수 시각·매도 시각과 같은 카드도 포함된다")
	void heldPeriodFinderIncludesCardsEndingExactlyAtTheBoundaries() {
		PriceMoveEvent atBuy = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(9, 25), HOLD_FROM, LocalTime.MIN));
		PriceMoveEvent atSell = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(14, 35), HOLD_TO, LocalTime.MIN));
		// 경계 밖 1분 — 대조군이다.
		priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(9, 20), HOLD_FROM.minusMinutes(1), LocalTime.MIN));
		priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(14, 41), HOLD_TO.plusMinutes(1), LocalTime.MIN));

		assertThat(findHeldCards(END_OF_DAY))
			.extracting(PriceMoveEvent::getId)
			.containsExactly(atBuy.getId(), atSell.getId());
	}

	// 게이트 ⑮ — 보유 구간이 이미 지난 시각이라도 근거 기사는 windowEnd 이후에 발행된 것이 붙으므로 게이트가 걸린다.
	@Test
	@DisplayName("보유 구간 안이어도 reveal_time이 상한을 넘은 카드는 빠지고, 상한을 올리면 나온다")
	void heldPeriodFinderAppliesTheRevealCutoff() {
		PriceMoveEvent revealed = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(11, 20), LocalTime.of(11, 25),
				LocalTime.of(11, 26)));
		PriceMoveEvent hidden = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(14, 5), LocalTime.of(14, 10),
				LocalTime.of(14, 41)));

		// 상한이 11:30인 조회 — 오후 카드는 아직 닫혀 있다.
		assertThat(findHeldCards(LocalTime.of(11, 30)))
			.extracting(PriceMoveEvent::getId)
			.containsExactly(revealed.getId());
		// 경계가 <= 이므로 정각에 열린다.
		assertThat(findHeldCards(LocalTime.of(11, 26)))
			.extracting(PriceMoveEvent::getId)
			.containsExactly(revealed.getId());
		assertThat(findHeldCards(LocalTime.of(11, 25, 59))).isEmpty();
		// 과거 서비스 날짜의 체결이면 그날 카드가 전부 열려 있어야 한다 — 호출부가 넘기는 "그날 끝" 상한이다.
		assertThat(findHeldCards(END_OF_DAY))
			.extracting(PriceMoveEvent::getId)
			.containsExactly(revealed.getId(), hidden.getId());
	}

	// "그날 끝" 상한을 LocalTime.MAX로 표현하면 카드가 예외도 로그도 없이 0건이 된다. Connector/J가
	// 23:59:59.999999999를 TIME으로 보내면서 MySQL이 소수 초를 올림해 00:00:00으로 접기 때문이다 —
	// 그러면 reveal_time <= 00:00:00이 되어 자정 카드만 남는다. 상한은 초 단위(23:59:59)로 만든다.
	@Test
	@DisplayName("상한을 LocalTime.MAX로 넘기면 MySQL이 00:00:00으로 접어 카드가 0건이 된다 — 이 값을 상한으로 쓰지 않는다")
	void localTimeMaxCollapsesToMidnightAndMatchesNoCard() {
		priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(11, 20), LocalTime.of(11, 25),
				LocalTime.of(11, 26)));

		// 드라이버가 실제로 무엇을 보내는지 — 값이 접히는 자리를 눈에 보이게 남긴다.
		assertThat(jdbcTemplate.queryForObject("select cast(? as char)", String.class, LocalTime.MAX))
			.isEqualTo("00:00:00");
		assertThat(jdbcTemplate.queryForObject("select cast(? as char)", String.class, END_OF_DAY))
			.isEqualTo("23:59:59");

		assertThat(findHeldCards(LocalTime.MAX)).isEmpty();
		assertThat(findHeldCards(END_OF_DAY)).hasSize(1);
	}

	// 이 응답에서는 카드 순서가 화면 순서만이 아니라 3번 항목의 반사실 기준 카드(보유 구간의 첫 카드)까지 정한다.
	//
	// 2차 키(id)를 지운 회귀는 이 단정으로 반드시 잡히지 않는다 — InnoDB가 흔히 PK 순서로 돌려주어 우연히
	// 통과한다. 보호는 파인더 이름과 계약 문장 양쪽에 남아 있다.
	@Test
	@DisplayName("보유 구간 카드는 window_start 오름차순 + id 오름차순이고 다른 종목·거래일은 섞이지 않는다")
	void heldPeriodFinderOrdersByWindowStartThenIdAndIsScopedToInstrumentAndTradeDate() {
		// 첫 분봉이 09:00인 날 갭 카드와 장중 첫 후보의 window_start가 정확히 같아진다 (§데이터 모델).
		PriceMoveEvent gap = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.OPENING_GAP, LocalTime.of(10, 0), LocalTime.of(10, 0), LocalTime.MIN));
		PriceMoveEvent intraday = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(10, 0), LocalTime.of(10, 5), LocalTime.MIN));
		PriceMoveEvent earlier = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(9, 30), LocalTime.of(9, 35), LocalTime.MIN));
		// 다른 원본 거래일 — 같은 시각이어도 섞이지 않는다.
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock, PriceMoveEventType.INTRADAY, ORIGIN_TRADE_DATE.plusDays(1), LocalTime.of(11, 20),
			LocalTime.of(11, 25), CHANGE_RATE, DETECTION_SCORE, "다른 거래일", NarrativeSource.LLM, LocalTime.MIN, NOW));
		// 다른 종목 — 같은 거래일·시각이어도 섞이지 않는다.
		Instrument otherStock = instrumentRepository.save(Instrument.create(
			Market.STOCK, "MOVE002", "테스트종목B", new BigDecimal("100"), 70000, true, NOW));
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			otherStock, PriceMoveEventType.INTRADAY, ORIGIN_TRADE_DATE, LocalTime.of(11, 20), LocalTime.of(11, 25),
			CHANGE_RATE, DETECTION_SCORE, "다른 종목", NarrativeSource.LLM, LocalTime.MIN, NOW));

		// 픽스처 전제 — 두 window_start가 실제로 같고 저장 순서가 갭 → 장중이다.
		assertThat(gap.getWindowStart()).isEqualTo(intraday.getWindowStart());
		assertThat(gap.getId()).isLessThan(intraday.getId());
		// 가장 이른 카드를 마지막에 저장했다 — window_start 정렬을 잃은 구현이면 순서가 어긋난다.
		assertThat(earlier.getId()).isGreaterThan(intraday.getId());

		assertThat(findHeldCards(END_OF_DAY))
			.extracting(PriceMoveEvent::getId)
			.containsExactly(earlier.getId(), gap.getId(), intraday.getId());
	}

	// --- 코인 쿨다운·일일 상한 조회 (spec 012 §탐지 알고리즘(코인), 이슈 #225 항목 3) ---

	@Test
	@DisplayName("findFirst는 이 종목·시장의 occurred_at 최댓값(가장 최근 카드)만 돌려준다")
	void findFirstByInstrumentIdAndMarketReturnsTheMostRecentCryptoCardOnly() {
		priceMoveEventRepository.saveAndFlush(newCryptoEvent(OCCURRED_AT.minusMinutes(10)));
		PriceMoveEvent latest = priceMoveEventRepository.saveAndFlush(newCryptoEvent(OCCURRED_AT));
		priceMoveEventRepository.saveAndFlush(newCryptoEvent(OCCURRED_AT.minusMinutes(5)));

		PriceMoveEvent found = priceMoveEventRepository
			.findFirstByInstrumentIdAndMarketOrderByOccurredAtDesc(crypto.getId(), Market.CRYPTO)
			.orElseThrow();

		assertThat(found.getId()).isEqualTo(latest.getId());
		assertThat(found.getOccurredAt()).isEqualTo(OCCURRED_AT);
	}

	@Test
	@DisplayName("findFirst는 다른 종목·다른 시장(STOCK) 카드를 섞지 않는다")
	void findFirstByInstrumentIdAndMarketIsScopedToInstrumentAndMarket() {
		// 주식 종목의 STOCK 카드 — market이 다르므로 후보가 아니다.
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock, PriceMoveEventType.INTRADAY, ORIGIN_TRADE_DATE,
			WINDOW_START, WINDOW_END, CHANGE_RATE, DETECTION_SCORE, "다른 시장", NarrativeSource.LLM, REVEAL_TIME, NOW));
		// 다른 코인 종목 — 시장은 같지만 종목이 다르므로 후보가 아니다.
		Instrument otherCrypto = instrumentRepository.save(Instrument.create(
			Market.CRYPTO, "MOVEETH", "테스트코인B", new BigDecimal("1"), 5000, true, NOW));
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createCrypto(
			otherCrypto, OCCURRED_AT, CHANGE_RATE, DETECTION_SCORE, "다른 종목", NarrativeSource.TEMPLATE, NOW));

		assertThat(priceMoveEventRepository
			.findFirstByInstrumentIdAndMarketOrderByOccurredAtDesc(crypto.getId(), Market.CRYPTO))
			.isEmpty();
	}

	@Test
	@DisplayName("이 종목·시장의 카드가 하나도 없으면 findFirst는 빈 Optional이다 — 쿨다운 없음으로 판정된다")
	void findFirstReturnsEmptyWhenNoCryptoCardExistsYet() {
		assertThat(priceMoveEventRepository
			.findFirstByInstrumentIdAndMarketOrderByOccurredAtDesc(crypto.getId(), Market.CRYPTO))
			.isEmpty();
	}

	@Test
	@DisplayName("countBy는 이 종목·시장·origin_trade_date(KST)가 모두 일치하는 카드만 센다")
	void countByInstrumentIdAndMarketAndOriginTradeDateCountsOnlyExactMatches() {
		LocalDate tradeDate = LocalDate.of(2026, 8, 4);
		priceMoveEventRepository.saveAndFlush(newCryptoEvent(LocalDateTime.of(tradeDate, LocalTime.of(10, 0))));
		priceMoveEventRepository.saveAndFlush(newCryptoEvent(LocalDateTime.of(tradeDate, LocalTime.of(14, 0))));
		// 다른 날짜 — 세지 않는다.
		priceMoveEventRepository
			.saveAndFlush(newCryptoEvent(LocalDateTime.of(tradeDate.plusDays(1), LocalTime.of(1, 0))));
		// 같은 날짜의 STOCK 카드 — market이 다르므로 세지 않는다.
		priceMoveEventRepository.saveAndFlush(newStockEvent(PriceMoveEventType.INTRADAY, WINDOW_START));

		long count = priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
			crypto.getId(), Market.CRYPTO, tradeDate);

		assertThat(count).isEqualTo(2);
	}

	// 함정 재현 — 자정을 넘긴 카드(23:58 생성)와 그 직후(00:03 생성) 카드는 origin_trade_date가 다른 날이라
	// 일일 상한 카운트가 서로 섞이면 안 된다(§C-9). 카운트 기준이 occurred_at 자체가 아니라 origin_trade_date라는
	// 것을 이 테스트가 못박는다.
	@Test
	@DisplayName("자정을 넘긴 카드는 origin_trade_date가 다음 날로 갈려 전날 일일 상한 카운트에 섞이지 않는다")
	void dailyLimitCountDoesNotLeakAcrossMidnightBecauseOriginTradeDateSplits() {
		LocalDateTime beforeMidnight = LocalDateTime.of(2026, 8, 3, 23, 58);
		LocalDateTime afterMidnight = LocalDateTime.of(2026, 8, 4, 0, 3);
		PriceMoveEvent late = priceMoveEventRepository.saveAndFlush(newCryptoEvent(beforeMidnight));
		PriceMoveEvent early = priceMoveEventRepository.saveAndFlush(newCryptoEvent(afterMidnight));

		// 팩토리가 파생한 origin_trade_date 자체가 이미 다른 날이어야 이 테스트의 전제가 성립한다.
		assertThat(late.getOriginTradeDate()).isEqualTo(LocalDate.of(2026, 8, 3));
		assertThat(early.getOriginTradeDate()).isEqualTo(LocalDate.of(2026, 8, 4));

		assertThat(priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
			crypto.getId(), Market.CRYPTO, LocalDate.of(2026, 8, 3))).isEqualTo(1);
		assertThat(priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
			crypto.getId(), Market.CRYPTO, LocalDate.of(2026, 8, 4))).isEqualTo(1);
	}

	// --- 코인 "최근 24시간" 카드 조회 (FEED-006, §C-2 ROLLING_24H, 이슈 #225 항목 4) ---

	private static final LocalDateTime QUERY_NOW = LocalDateTime.of(2026, 8, 5, 15, 0, 0);

	private List<PriceMoveEvent> findRecent24Hours() {
		return priceMoveEventRepository
			.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
				crypto.getId(), Market.CRYPTO, QUERY_NOW.minusHours(24), QUERY_NOW);
	}

	// BETWEEN이 양 끝을 포함한다 — 부등호가 하나라도 배타로 바뀌면 정확히 24시간 전/후 경계의 카드가 조용히
	// 사라진다.
	@Test
	@DisplayName("정확히 24시간 전 카드와 지금(now) 카드가 둘 다 포함된다")
	void includesCardsExactlyAtTheTwentyFourHourBoundary() {
		PriceMoveEvent exactlyTwentyFourHoursAgo = priceMoveEventRepository.saveAndFlush(
			newCryptoEvent(QUERY_NOW.minusHours(24)));
		PriceMoveEvent exactlyNow = priceMoveEventRepository.saveAndFlush(newCryptoEvent(QUERY_NOW));

		assertThat(findRecent24Hours())
			.extracting(PriceMoveEvent::getId)
			.containsExactly(exactlyTwentyFourHoursAgo.getId(), exactlyNow.getId());
	}

	// 경계 밖 1초 — 대조군이다. 24시간 하고도 1초 더 지난 카드와, now보다 1초 뒤(미래) 카드는 빠진다.
	@Test
	@DisplayName("24시간보다 1초라도 이전이거나 now보다 1초라도 이후인 카드는 빠진다")
	void excludesCardsJustOutsideTheTwentyFourHourWindow() {
		priceMoveEventRepository.saveAndFlush(newCryptoEvent(QUERY_NOW.minusHours(24).minusSeconds(1)));
		priceMoveEventRepository.saveAndFlush(newCryptoEvent(QUERY_NOW.plusSeconds(1)));
		PriceMoveEvent inside = priceMoveEventRepository.saveAndFlush(newCryptoEvent(QUERY_NOW.minusHours(1)));

		assertThat(findRecent24Hours())
			.extracting(PriceMoveEvent::getId)
			.containsExactly(inside.getId());
	}

	@Test
	@DisplayName("다른 종목·다른 시장(STOCK) 카드는 24시간 창 안에 있어도 섞이지 않는다")
	void excludesOtherInstrumentAndOtherMarketWithinTheWindow() {
		priceMoveEventRepository.saveAndFlush(newStockEvent(PriceMoveEventType.INTRADAY, WINDOW_START));
		Instrument otherCrypto = instrumentRepository.save(Instrument.create(
			Market.CRYPTO, "MOVEETH2", "테스트코인C", new BigDecimal("1"), 5000, true, NOW));
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createCrypto(
			otherCrypto, QUERY_NOW.minusHours(1), CHANGE_RATE, DETECTION_SCORE, "다른 종목",
			NarrativeSource.TEMPLATE, NOW));

		assertThat(findRecent24Hours()).isEmpty();
	}

	// 2차 키(id)가 없으면 같은 occurredAt을 가진 카드의 순서가 DB 임의 순서가 된다 — 위 주식 파인더들과 같은 이유.
	@Test
	@DisplayName("occurredAt이 같으면 저장 순서(id) 오름차순으로 2차 정렬된다")
	void ordersByOccurredAtAscThenByIdAscOnTies() {
		LocalDateTime sameInstant = QUERY_NOW.minusHours(2);
		PriceMoveEvent first = priceMoveEventRepository.saveAndFlush(newCryptoEvent(sameInstant));
		PriceMoveEvent second = priceMoveEventRepository.saveAndFlush(newCryptoEvent(sameInstant));
		PriceMoveEvent earlier = priceMoveEventRepository.saveAndFlush(newCryptoEvent(QUERY_NOW.minusHours(3)));

		assertThat(findRecent24Hours())
			.extracting(PriceMoveEvent::getId)
			.containsExactly(earlier.getId(), first.getId(), second.getId());
	}

	// §C-5 "카드(코인) — 없음" — 이 파인더 자체가 reveal_time을 조건에 넣지 않는다. 카드는 생성되는 즉시(occurred_at이
	// 창 안에 들어오는 즉시) 노출 대상이며, 주식처럼 별도 시각까지 기다리지 않는다.
	@Test
	@DisplayName("방금 생성된 카드도 노출 게이트 없이 즉시 조회된다")
	void includesJustCreatedCardWithoutAnyRevealGate() {
		PriceMoveEvent justCreated = priceMoveEventRepository.saveAndFlush(newCryptoEvent(QUERY_NOW));

		assertThat(findRecent24Hours()).extracting(PriceMoveEvent::getId).containsExactly(justCreated.getId());
	}
}
