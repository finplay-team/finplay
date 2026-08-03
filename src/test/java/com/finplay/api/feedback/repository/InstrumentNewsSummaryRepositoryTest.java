// 실제 MySQL에서 instrument_news_summaries의 UNIQUE(instrument_id, origin_trade_date, scope)와 summary NULL 허용을 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.feedback.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.domain.InstrumentNewsSummary;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
class InstrumentNewsSummaryRepositoryTest {

	@Autowired
	private InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Instrument stock;
	private Instrument crypto;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 3);
	private static final LocalDate NEXT_TRADE_DATE = LocalDate.of(2026, 8, 4);
	private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 3, 8, 30, 0);
	private static final String SUMMARY = "실적 발표를 앞두고 관망세가 이어졌습니다.";

	@BeforeEach
	void setUp() {
		// V7 시드(005930 등)와 겹치지 않는 테스트 전용 심볼을 사용한다 — UNIQUE(symbol) 충돌 방지.
		stock = instrumentRepository.save(Instrument.create(
			Market.STOCK, "SUM001", "테스트종목A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		crypto = instrumentRepository.save(Instrument.create(
			Market.CRYPTO, "SUMBTC", "테스트코인", new BigDecimal("1"), 5000, true, LocalDateTime.now()));
	}

	private InstrumentNewsSummary newSummary(
		Instrument instrument, LocalDate originTradeDate, NewsSummaryScope scope) {
		return InstrumentNewsSummary.create(
			instrument, originTradeDate, scope, SUMMARY, NarrativeSource.LLM, GENERATED_AT);
	}

	// --- 검증 ① 같은 종목·거래일에 scope만 다른 행 2건이 공존한다 ---

	@Test
	@DisplayName("같은 종목·거래일에 PRE_MARKET과 FULL 요약 2건이 공존한다")
	void preMarketAndFullSummariesCoexistForTheSameInstrumentAndTradeDate() {
		instrumentNewsSummaryRepository.saveAndFlush(
			newSummary(stock, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET));
		instrumentNewsSummaryRepository.saveAndFlush(newSummary(stock, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL));

		List<InstrumentNewsSummary> all = instrumentNewsSummaryRepository.findAll();

		assertThat(all).hasSize(2);
		assertThat(all).extracting(InstrumentNewsSummary::getScope)
			.containsExactlyInAnyOrder(NewsSummaryScope.PRE_MARKET, NewsSummaryScope.FULL);
	}

	// --- 검증 ② 같은 (종목, 거래일, scope) 2건째는 유니크에 걸린다 ---

	@Test
	@DisplayName("같은 (종목, 거래일, scope) 2건째는 유니크 제약에 걸린다 — 이 제약이 UPSERT를 성립시킨다")
	void databaseRejectsDuplicateInstrumentTradeDateAndScope() {
		instrumentNewsSummaryRepository.saveAndFlush(
			newSummary(stock, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET));

		InstrumentNewsSummary duplicate = newSummary(stock, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);

		assertThatThrownBy(() -> instrumentNewsSummaryRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("거래일이 다르면 같은 종목·scope 요약이 날짜별로 쌓인다")
	void sameInstrumentAndScopeCoexistAcrossDifferentTradeDates() {
		instrumentNewsSummaryRepository.saveAndFlush(
			newSummary(stock, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET));
		instrumentNewsSummaryRepository.saveAndFlush(
			newSummary(stock, NEXT_TRADE_DATE, NewsSummaryScope.PRE_MARKET));

		assertThat(instrumentNewsSummaryRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("종목이 다르면 같은 거래일·scope 요약이 종목별로 공존한다")
	void differentInstrumentsCoexistOnTheSameTradeDateAndScope() {
		instrumentNewsSummaryRepository.saveAndFlush(newSummary(stock, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL));
		instrumentNewsSummaryRepository.saveAndFlush(newSummary(crypto, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL));

		assertThat(instrumentNewsSummaryRepository.count()).isEqualTo(2);
	}

	// --- 검증 ④ summary가 NULL이고 narrative_source가 NONE인 행이 저장된다 ---

	@Test
	@DisplayName("summary가 NULL이고 narrative_source가 NONE인 행이 저장된다 — 조회가 UNAVAILABLE을 내려면 이 행이 남아야 한다")
	void summaryRowWithNullTextAndNoneSourceIsPersisted() {
		// 요약에는 템플릿이 없어 후검증 재생성 후에도 걸리면 문장이 빈다 (§C-4). 컬럼이 NOT NULL이면 이 행이
		// 저장되지 않고, 그러면 조회가 "생성 실패"와 "아직 생성 안 됨"을 구분할 수 없다.
		Long id = instrumentNewsSummaryRepository.saveAndFlush(InstrumentNewsSummary.create(
			stock, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL, null, NarrativeSource.NONE, GENERATED_AT)).getId();

		InstrumentNewsSummary found = instrumentNewsSummaryRepository.findById(id).orElseThrow();

		assertThat(found.getSummary()).isNull();
		assertThat(found.getNarrativeSource()).isEqualTo(NarrativeSource.NONE);
		assertThat(found.getGeneratedAt()).isEqualTo(GENERATED_AT);
		// 엔티티가 아니라 실제 컬럼이 NULL인지까지 확인한다 (빈 문자열로 저장되면 조회 판정이 갈린다).
		Map<String, Object> row = jdbcTemplate.queryForMap(
			"select summary, narrative_source from instrument_news_summaries where id = ?", id);
		assertThat(row.get("summary")).isNull();
		assertThat(row.get("narrative_source")).isEqualTo("NONE");
	}

	// --- 매핑 ---

	@Test
	@DisplayName("코인 요약은 ROLLING_24H로 저장되고 origin_trade_date가 채워진다")
	void cryptoSummaryIsStoredWithRollingScopeAndAFilledOriginTradeDate() {
		Long id = instrumentNewsSummaryRepository.saveAndFlush(
			newSummary(crypto, ORIGIN_TRADE_DATE, NewsSummaryScope.ROLLING_24H)).getId();

		InstrumentNewsSummary found = instrumentNewsSummaryRepository.findById(id).orElseThrow();

		assertThat(found.getInstrument().getId()).isEqualTo(crypto.getId());
		assertThat(found.getScope()).isEqualTo(NewsSummaryScope.ROLLING_24H);
		// 코인도 비우지 않는다 — NULL이면 유니크가 중복을 허용해 UPSERT가 새 행을 매시 쌓는다 (§C-9).
		assertThat(found.getOriginTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
	}

	@Test
	@DisplayName("origin_trade_date를 비우면 엔티티 제약에서 저장이 막힌다")
	void originTradeDateCannotBeNull() {
		// 이 단정은 엔티티의 @Column(nullable = false)까지만 닿는다 — Hibernate가 flush 전에 던져 SQL이 나가지
		// 않으므로 V13의 NOT NULL 여부는 여기서 증명되지 않는다. 그 축은 FeedbackSchemaConstraintsTest가 본다.
		InstrumentNewsSummary withoutDate = InstrumentNewsSummary.create(
			crypto, null, NewsSummaryScope.ROLLING_24H, SUMMARY, NarrativeSource.LLM, GENERATED_AT);

		assertThatThrownBy(() -> instrumentNewsSummaryRepository.saveAndFlush(withoutDate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("scope와 narrative_source가 이름 문자열로 저장된다")
	void enumColumnsStoreTheirNamesAsStrings() {
		// ORDINAL로 매핑되면 VARCHAR(20)에 "0"이 들어가도 MySQL은 조용히 받는다. 실제 저장 문자열을 확인한다.
		instrumentNewsSummaryRepository.saveAndFlush(
			newSummary(stock, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET));

		Map<String, Object> row = jdbcTemplate.queryForMap(
			"select scope, narrative_source from instrument_news_summaries");

		assertThat(row.get("scope")).isEqualTo("PRE_MARKET");
		assertThat(row.get("narrative_source")).isEqualTo("LLM");
	}

	@Test
	@DisplayName("summary는 varchar(255)를 넘는 요약도 잘리지 않고 그대로 복원된다")
	void summaryColumnKeepsTextLongerThanTwoHundredFiftyFiveCharacters() {
		String longSummary = "실적 발표를 앞두고 관망세가 이어졌습니다. ".repeat(30);
		assertThat(longSummary.length()).isGreaterThan(255);

		Long id = instrumentNewsSummaryRepository.saveAndFlush(InstrumentNewsSummary.create(
			stock, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL, longSummary, NarrativeSource.LLM, GENERATED_AT))
			.getId();

		assertThat(instrumentNewsSummaryRepository.findById(id).orElseThrow().getSummary()).isEqualTo(longSummary);
	}
}
