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
	private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 8, 4, 0, 3, 0);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 4, 0, 3, 30);
	private static final BigDecimal CHANGE_RATE = new BigDecimal("0.052500");
	private static final BigDecimal DETECTION_SCORE = new BigDecimal("3.2500");

	@BeforeEach
	void setUp() {
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
		return PriceMoveEvent.createCrypto(
			crypto,
			occurredAt,
			CHANGE_RATE,
			DETECTION_SCORE,
			"대형 거래소 상장 소식이 있었습니다.",
			NarrativeSource.TEMPLATE,
			NOW);
	}


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
		assertThat(found.getOccurredAt()).isEqualTo(OCCURRED_AT);
		assertThat(found.getOccurredAt().toLocalDate()).isEqualTo(LocalDate.of(2026, 8, 4));
		assertThat(found.getWindowStart()).isNull();
		assertThat(found.getWindowEnd()).isNull();
		assertThat(found.getRevealTime()).isNull();
		assertThat(found.getOriginTradeDate()).isEqualTo(LocalDate.of(2026, 8, 4));
		assertThat(found.getOriginTradeDate()).isNotEqualTo(ORIGIN_TRADE_DATE);
	}


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
		assertThat(row.get("event_type")).isEqualTo("INTRADAY");
		assertThat(row.get("window_start")).isNull();
		assertThat(row.get("window_end")).isNull();
		assertThat(row.get("reveal_time")).isNull();
		assertThat(row.get("occurred_at")).isNotNull();
	}

	@Test
	@DisplayName("createStock에 코인 종목을 넘기면 예외로 막힌다 — market 컬럼과 종목의 시장이 어긋난 카드는 만들 수 없다")
	void createStockRejectsACryptoInstrument() {
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


	@Test
	@DisplayName("window_start가 09:00으로 같아도 event_type이 다르면 장중 카드와 시가 갭 카드가 공존한다")
	void intradayAndOpeningGapCardsCoexistAtTheSameWindowStart() {
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
		priceMoveEventRepository.saveAndFlush(newCryptoEvent(OCCURRED_AT));
		priceMoveEventRepository.saveAndFlush(newCryptoEvent(OCCURRED_AT.plusMinutes(1)));

		assertThat(priceMoveEventRepository.count()).isEqualTo(2);
	}


	private static final LocalTime HOLD_FROM = LocalTime.of(9, 30);
	private static final LocalTime HOLD_TO = LocalTime.of(14, 40);

	private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

	private List<PriceMoveEvent> findHeldCards(LocalTime revealCutoff) {
		return priceMoveEventRepository
			.findByInstrumentIdAndOriginTradeDateAndWindowEndBetweenAndRevealTimeLessThanEqualOrderByWindowStartAscIdAsc(
				stock.getId(), ORIGIN_TRADE_DATE, HOLD_FROM, HOLD_TO, revealCutoff);
	}

	@Test
	@DisplayName("보유 구간 파인더는 window_start가 아니라 window_end로 좁힌다")
	void heldPeriodFinderNarrowsByWindowEndNotWindowStart() {
		PriceMoveEvent startedBeforeBuy = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(9, 25), LocalTime.of(9, 31), LocalTime.MIN));
		priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(14, 35), LocalTime.of(14, 45), LocalTime.MIN));
		PriceMoveEvent inside = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(11, 20), LocalTime.of(11, 25), LocalTime.MIN));

		assertThat(findHeldCards(END_OF_DAY))
			.extracting(PriceMoveEvent::getId)
			.containsExactly(startedBeforeBuy.getId(), inside.getId());
	}

	@Test
	@DisplayName("window_end가 매수 시각·매도 시각과 같은 카드도 포함된다")
	void heldPeriodFinderIncludesCardsEndingExactlyAtTheBoundaries() {
		PriceMoveEvent atBuy = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(9, 25), HOLD_FROM, LocalTime.MIN));
		PriceMoveEvent atSell = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(14, 35), HOLD_TO, LocalTime.MIN));
		priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(9, 20), HOLD_FROM.minusMinutes(1), LocalTime.MIN));
		priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(14, 41), HOLD_TO.plusMinutes(1), LocalTime.MIN));

		assertThat(findHeldCards(END_OF_DAY))
			.extracting(PriceMoveEvent::getId)
			.containsExactly(atBuy.getId(), atSell.getId());
	}

	@Test
	@DisplayName("보유 구간 안이어도 reveal_time이 상한을 넘은 카드는 빠지고, 상한을 올리면 나온다")
	void heldPeriodFinderAppliesTheRevealCutoff() {
		PriceMoveEvent revealed = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(11, 20), LocalTime.of(11, 25),
				LocalTime.of(11, 26)));
		PriceMoveEvent hidden = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(14, 5), LocalTime.of(14, 10),
				LocalTime.of(14, 41)));

		assertThat(findHeldCards(LocalTime.of(11, 30)))
			.extracting(PriceMoveEvent::getId)
			.containsExactly(revealed.getId());
		assertThat(findHeldCards(LocalTime.of(11, 26)))
			.extracting(PriceMoveEvent::getId)
			.containsExactly(revealed.getId());
		assertThat(findHeldCards(LocalTime.of(11, 25, 59))).isEmpty();
		assertThat(findHeldCards(END_OF_DAY))
			.extracting(PriceMoveEvent::getId)
			.containsExactly(revealed.getId(), hidden.getId());
	}

	@Test
	@DisplayName("상한을 LocalTime.MAX로 넘기면 MySQL이 00:00:00으로 접어 카드가 0건이 된다 — 이 값을 상한으로 쓰지 않는다")
	void localTimeMaxCollapsesToMidnightAndMatchesNoCard() {
		priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(11, 20), LocalTime.of(11, 25),
				LocalTime.of(11, 26)));

		assertThat(jdbcTemplate.queryForObject("select cast(? as char)", String.class, LocalTime.MAX))
			.isEqualTo("00:00:00");
		assertThat(jdbcTemplate.queryForObject("select cast(? as char)", String.class, END_OF_DAY))
			.isEqualTo("23:59:59");

		assertThat(findHeldCards(LocalTime.MAX)).isEmpty();
		assertThat(findHeldCards(END_OF_DAY)).hasSize(1);
	}

	@Test
	@DisplayName("보유 구간 카드는 window_start 오름차순 + id 오름차순이고 다른 종목·거래일은 섞이지 않는다")
	void heldPeriodFinderOrdersByWindowStartThenIdAndIsScopedToInstrumentAndTradeDate() {
		PriceMoveEvent gap = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.OPENING_GAP, LocalTime.of(10, 0), LocalTime.of(10, 0), LocalTime.MIN));
		PriceMoveEvent intraday = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(10, 0), LocalTime.of(10, 5), LocalTime.MIN));
		PriceMoveEvent earlier = priceMoveEventRepository.saveAndFlush(
			newStockEvent(PriceMoveEventType.INTRADAY, LocalTime.of(9, 30), LocalTime.of(9, 35), LocalTime.MIN));
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock, PriceMoveEventType.INTRADAY, ORIGIN_TRADE_DATE.plusDays(1), LocalTime.of(11, 20),
			LocalTime.of(11, 25), CHANGE_RATE, DETECTION_SCORE, "다른 거래일", NarrativeSource.LLM, LocalTime.MIN, NOW));
		Instrument otherStock = instrumentRepository.save(Instrument.create(
			Market.STOCK, "MOVE002", "테스트종목B", new BigDecimal("100"), 70000, true, NOW));
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			otherStock, PriceMoveEventType.INTRADAY, ORIGIN_TRADE_DATE, LocalTime.of(11, 20), LocalTime.of(11, 25),
			CHANGE_RATE, DETECTION_SCORE, "다른 종목", NarrativeSource.LLM, LocalTime.MIN, NOW));

		assertThat(gap.getWindowStart()).isEqualTo(intraday.getWindowStart());
		assertThat(gap.getId()).isLessThan(intraday.getId());
		assertThat(earlier.getId()).isGreaterThan(intraday.getId());

		assertThat(findHeldCards(END_OF_DAY))
			.extracting(PriceMoveEvent::getId)
			.containsExactly(earlier.getId(), gap.getId(), intraday.getId());
	}


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
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createStock(
			stock, PriceMoveEventType.INTRADAY, ORIGIN_TRADE_DATE,
			WINDOW_START, WINDOW_END, CHANGE_RATE, DETECTION_SCORE, "다른 시장", NarrativeSource.LLM, REVEAL_TIME, NOW));
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
		priceMoveEventRepository
			.saveAndFlush(newCryptoEvent(LocalDateTime.of(tradeDate.plusDays(1), LocalTime.of(1, 0))));
		priceMoveEventRepository.saveAndFlush(newStockEvent(PriceMoveEventType.INTRADAY, WINDOW_START));

		long count = priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
			crypto.getId(), Market.CRYPTO, tradeDate);

		assertThat(count).isEqualTo(2);
	}

	@Test
	@DisplayName("자정을 넘긴 카드는 origin_trade_date가 다음 날로 갈려 전날 일일 상한 카운트에 섞이지 않는다")
	void dailyLimitCountDoesNotLeakAcrossMidnightBecauseOriginTradeDateSplits() {
		LocalDateTime beforeMidnight = LocalDateTime.of(2026, 8, 3, 23, 58);
		LocalDateTime afterMidnight = LocalDateTime.of(2026, 8, 4, 0, 3);
		PriceMoveEvent late = priceMoveEventRepository.saveAndFlush(newCryptoEvent(beforeMidnight));
		PriceMoveEvent early = priceMoveEventRepository.saveAndFlush(newCryptoEvent(afterMidnight));

		assertThat(late.getOriginTradeDate()).isEqualTo(LocalDate.of(2026, 8, 3));
		assertThat(early.getOriginTradeDate()).isEqualTo(LocalDate.of(2026, 8, 4));

		assertThat(priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
			crypto.getId(), Market.CRYPTO, LocalDate.of(2026, 8, 3))).isEqualTo(1);
		assertThat(priceMoveEventRepository.countByInstrumentIdAndMarketAndOriginTradeDate(
			crypto.getId(), Market.CRYPTO, LocalDate.of(2026, 8, 4))).isEqualTo(1);
	}


	private static final LocalDateTime QUERY_NOW = LocalDateTime.of(2026, 8, 5, 15, 0, 0);

	private List<PriceMoveEvent> findRecent24Hours() {
		return priceMoveEventRepository
			.findByInstrumentIdAndMarketAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
				crypto.getId(), Market.CRYPTO, QUERY_NOW.minusHours(24), QUERY_NOW);
	}

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

	@Test
	@DisplayName("방금 생성된 카드도 노출 게이트 없이 즉시 조회된다")
	void includesJustCreatedCardWithoutAnyRevealGate() {
		PriceMoveEvent justCreated = priceMoveEventRepository.saveAndFlush(newCryptoEvent(QUERY_NOW));

		assertThat(findRecent24Hours()).extracting(PriceMoveEvent::getId).containsExactly(justCreated.getId());
	}
}
