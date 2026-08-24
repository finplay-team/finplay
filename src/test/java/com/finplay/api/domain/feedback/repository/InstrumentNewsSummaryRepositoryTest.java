// 실제 MySQL에서 instrument_news_summaries의 UNIQUE(instrument_id, origin_trade_date, scope)와 summary NULL 허용을 검증하는 JPA 슬라이스 테스트다.
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

	// --- 생성 쪽 중복 판정 (이슈 #188 항목 4) ---
	//
	// 주식은 UPSERT가 아니라 "존재 시 건너뜀"이라 이 파인더가 배치 ⑤의 앞선 방어선이다. 세 축 중 하나라도
	// 빠지면 PRE_MARKET을 만든 뒤 FULL이 "이미 있다"로 접혀 전장 요약이 영영 생기지 않는데, 예외도 로그도
	// 남지 않고 조회만 EMPTY로 보인다.
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

	// --- 코인 조회·재생성 판정 파인더 (이슈 #188 항목 7 — 배치 ⑪) ---
	//
	// "오늘 날짜 행"으로 찾으면 매일 00:00~00:05와 배치 실패 시각마다 화면이 빈다. 그래서 generated_at
	// 최신 1행이며, 그 규칙이 실제 정렬로 성립하는지는 날짜가 갈린 행 위에서만 드러난다.
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

	// generated_at 동률은 배치가 같은 초에 두 번 도는 드문 경우지만, 2차 키가 없으면 결과가 비결정적이라
	// 조회가 실행마다 다른 문장을 준다.
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
	@DisplayName("origin_trade_date를 비우면 저장 자체가 실패한다")
	void originTradeDateCannotBeNull() {
		// INSERT가 실제로 DB까지 나가 MySQL의 NOT NULL에 걸린다 — spring-boot-starter-validation이 있어
		// Hibernate가 check_nullability를 끄기 때문이다. 이 단정은 V13의 컬럼 속성까지 닿는다.
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
