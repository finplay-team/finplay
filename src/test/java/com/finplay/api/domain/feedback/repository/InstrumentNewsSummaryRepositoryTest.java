package com.finplay.api.domain.feedback.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
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
	@DisplayName("existsByInstrumentIdAndOriginTradeDateAndScope가 유니크와 같은 세 축으로만 참이 된다")
	void existsByInstrumentTradeDateAndScopeMatchesTheUniqueAxis() {
		instrumentNewsSummaryRepository.saveAndFlush(
			newSummary(stock, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET));

		assertThat(instrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope(
			stock.getId(), ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET)).isTrue();
		assertThat(instrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope(
			stock.getId(), ORIGIN_TRADE_DATE, NewsSummaryScope.FULL))
			.as("범위가 다르면 별개 행이다 — 참이면 FULL 요약이 영영 생기지 않는다")
			.isFalse();
		assertThat(instrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope(
			stock.getId(), NEXT_TRADE_DATE, NewsSummaryScope.PRE_MARKET)).isFalse();
		assertThat(instrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope(
			crypto.getId(), ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET)).isFalse();
	}

	@Test
	@DisplayName("findFirstBy…OrderByGeneratedAtDesc가 날짜가 달라도 generated_at 최신 행을 준다")
	void findsTheLatestGeneratedRowAcrossDates() {
		instrumentNewsSummaryRepository.saveAndFlush(InstrumentNewsSummary.create(
			crypto, ORIGIN_TRADE_DATE, NewsSummaryScope.ROLLING_24H, "어제 23시 05분 요약",
			NarrativeSource.LLM, LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(23, 5))));
		instrumentNewsSummaryRepository.saveAndFlush(InstrumentNewsSummary.create(
			crypto, NEXT_TRADE_DATE, NewsSummaryScope.ROLLING_24H, "오늘 00시 05분 요약",
			NarrativeSource.LLM, LocalDateTime.of(NEXT_TRADE_DATE, LocalTime.of(0, 5))));

		assertThat(instrumentNewsSummaryRepository
			.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(
				crypto.getId(), NewsSummaryScope.ROLLING_24H))
			.get()
			.extracting(InstrumentNewsSummary::getSummary)
			.isEqualTo("오늘 00시 05분 요약");
	}

	@Test
	@DisplayName("findFirstBy…는 다른 종목·다른 범위의 행을 주지 않는다")
	void latestRowFinderFiltersByInstrumentAndScope() {
		instrumentNewsSummaryRepository.saveAndFlush(
			newSummary(stock, ORIGIN_TRADE_DATE, NewsSummaryScope.ROLLING_24H));
		instrumentNewsSummaryRepository.saveAndFlush(
			newSummary(crypto, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET));

		assertThat(instrumentNewsSummaryRepository
			.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(
				crypto.getId(), NewsSummaryScope.ROLLING_24H))
			.isEmpty();
	}

	@Test
	@DisplayName("generated_at이 같으면 id 내림차순으로 갈라 결과가 결정적이다")
	void breaksGeneratedAtTiesByIdDescending() {
		instrumentNewsSummaryRepository.saveAndFlush(InstrumentNewsSummary.create(
			crypto, ORIGIN_TRADE_DATE, NewsSummaryScope.ROLLING_24H, "먼저 저장", NarrativeSource.LLM,
			GENERATED_AT));
		Long laterId = instrumentNewsSummaryRepository.saveAndFlush(InstrumentNewsSummary.create(
			crypto, NEXT_TRADE_DATE, NewsSummaryScope.ROLLING_24H, "나중 저장", NarrativeSource.LLM,
			GENERATED_AT)).getId();

		assertThat(instrumentNewsSummaryRepository
			.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(
				crypto.getId(), NewsSummaryScope.ROLLING_24H))
			.get()
			.extracting(InstrumentNewsSummary::getId)
			.isEqualTo(laterId);
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

	@Test
	@DisplayName("summary가 NULL이고 narrative_source가 NONE인 행이 저장된다 — 조회가 UNAVAILABLE을 내려면 이 행이 남아야 한다")
	void summaryRowWithNullTextAndNoneSourceIsPersisted() {
		Long id = instrumentNewsSummaryRepository.saveAndFlush(InstrumentNewsSummary.create(
			stock, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL, null, NarrativeSource.NONE, GENERATED_AT)).getId();

		InstrumentNewsSummary found = instrumentNewsSummaryRepository.findById(id).orElseThrow();

		assertThat(found.getSummary()).isNull();
		assertThat(found.getNarrativeSource()).isEqualTo(NarrativeSource.NONE);
		assertThat(found.getGeneratedAt()).isEqualTo(GENERATED_AT);
		Map<String, Object> row = jdbcTemplate.queryForMap(
			"select summary, narrative_source from instrument_news_summaries where id = ?", id);
		assertThat(row.get("summary")).isNull();
		assertThat(row.get("narrative_source")).isEqualTo("NONE");
	}

	@Test
	@DisplayName("코인 요약은 ROLLING_24H로 저장되고 origin_trade_date가 채워진다")
	void cryptoSummaryIsStoredWithRollingScopeAndAFilledOriginTradeDate() {
		Long id = instrumentNewsSummaryRepository.saveAndFlush(
			newSummary(crypto, ORIGIN_TRADE_DATE, NewsSummaryScope.ROLLING_24H)).getId();

		InstrumentNewsSummary found = instrumentNewsSummaryRepository.findById(id).orElseThrow();

		assertThat(found.getInstrument().getId()).isEqualTo(crypto.getId());
		assertThat(found.getScope()).isEqualTo(NewsSummaryScope.ROLLING_24H);
		assertThat(found.getOriginTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
	}

	@Test
	@DisplayName("origin_trade_date를 비우면 저장 자체가 실패한다")
	void originTradeDateCannotBeNull() {
		InstrumentNewsSummary withoutDate = InstrumentNewsSummary.create(
			crypto, null, NewsSummaryScope.ROLLING_24H, SUMMARY, NarrativeSource.LLM, GENERATED_AT);

		assertThatThrownBy(() -> instrumentNewsSummaryRepository.saveAndFlush(withoutDate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("scope와 narrative_source가 이름 문자열로 저장된다")
	void enumColumnsStoreTheirNamesAsStrings() {
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
