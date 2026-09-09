package com.finplay.api.domain.feedback.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.market.entity.Market;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
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
class MarketBriefingRepositoryTest {

	@Autowired
	private MarketBriefingRepository marketBriefingRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 3);
	private static final LocalDate NEXT_TRADE_DATE = LocalDate.of(2026, 8, 4);
	private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 3, 8, 30, 0);
	private static final String SUMMARY = "간밤 미국 증시가 강세로 마감했습니다.";

	private MarketBriefing newBriefing(Market market, LocalDate originTradeDate) {
		return MarketBriefing.create(market, originTradeDate, SUMMARY, NarrativeSource.LLM, GENERATED_AT);
	}


	@Test
	@DisplayName("같은 (시장, 거래일) 브리핑 2건째는 유니크 제약에 걸린다 — 이 제약이 UPSERT를 성립시킨다")
	void databaseRejectsDuplicateMarketAndTradeDate() {
		marketBriefingRepository.saveAndFlush(newBriefing(Market.STOCK, ORIGIN_TRADE_DATE));

		MarketBriefing duplicate = newBriefing(Market.STOCK, ORIGIN_TRADE_DATE);

		assertThatThrownBy(() -> marketBriefingRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("existsByMarketAndOriginTradeDate가 유니크와 같은 두 축으로만 참이 된다")
	void existsByMarketAndOriginTradeDateMatchesTheUniqueAxis() {
		marketBriefingRepository.saveAndFlush(newBriefing(Market.STOCK, ORIGIN_TRADE_DATE));

		assertThat(marketBriefingRepository.existsByMarketAndOriginTradeDate(Market.STOCK, ORIGIN_TRADE_DATE))
			.isTrue();
		assertThat(marketBriefingRepository.existsByMarketAndOriginTradeDate(Market.STOCK, NEXT_TRADE_DATE))
			.as("거래일이 다르면 매일 새 브리핑이 만들어져야 한다")
			.isFalse();
		assertThat(marketBriefingRepository.existsByMarketAndOriginTradeDate(Market.CRYPTO, ORIGIN_TRADE_DATE))
			.as("시장이 다르면 별개 행이다 — 여기가 참이면 코인 브리핑이 영영 생기지 않는다")
			.isFalse();
	}


	@Test
	@DisplayName("findFirstByMarketOrderByGeneratedAtDesc가 날짜가 달라도 최신 행을 준다")
	void findsTheLatestGeneratedBriefingAcrossDates() {
		marketBriefingRepository.saveAndFlush(MarketBriefing.create(
			Market.CRYPTO, ORIGIN_TRADE_DATE, "어제 23시 05분 브리핑", NarrativeSource.LLM,
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(23, 5))));
		marketBriefingRepository.saveAndFlush(MarketBriefing.create(
			Market.CRYPTO, NEXT_TRADE_DATE, "오늘 00시 05분 브리핑", NarrativeSource.LLM,
			LocalDateTime.of(NEXT_TRADE_DATE, LocalTime.of(0, 5))));

		assertThat(marketBriefingRepository.findFirstByMarketOrderByGeneratedAtDescIdDesc(Market.CRYPTO))
			.get()
			.extracting(MarketBriefing::getSummary)
			.isEqualTo("오늘 00시 05분 브리핑");
	}

	@Test
	@DisplayName("findFirstByMarket…은 다른 시장의 행을 주지 않는다")
	void latestBriefingFinderFiltersByMarket() {
		marketBriefingRepository.saveAndFlush(newBriefing(Market.STOCK, ORIGIN_TRADE_DATE));

		assertThat(marketBriefingRepository.findFirstByMarketOrderByGeneratedAtDescIdDesc(Market.CRYPTO))
			.isEmpty();
	}

	@Test
	@DisplayName("같은 거래일이라도 시장이 다르면 주식·코인 브리핑이 공존한다")
	void stockAndCryptoBriefingsCoexistOnTheSameTradeDate() {
		marketBriefingRepository.saveAndFlush(newBriefing(Market.STOCK, ORIGIN_TRADE_DATE));
		marketBriefingRepository.saveAndFlush(newBriefing(Market.CRYPTO, ORIGIN_TRADE_DATE));

		List<MarketBriefing> all = marketBriefingRepository.findAll();

		assertThat(all).hasSize(2);
		assertThat(all).extracting(MarketBriefing::getMarket)
			.containsExactlyInAnyOrder(Market.STOCK, Market.CRYPTO);
	}

	@Test
	@DisplayName("거래일이 다르면 같은 시장 브리핑이 날짜별로 쌓인다")
	void sameMarketBriefingsCoexistAcrossDifferentTradeDates() {
		marketBriefingRepository.saveAndFlush(newBriefing(Market.STOCK, ORIGIN_TRADE_DATE));
		marketBriefingRepository.saveAndFlush(newBriefing(Market.STOCK, NEXT_TRADE_DATE));

		assertThat(marketBriefingRepository.count()).isEqualTo(2);
	}


	@Test
	@DisplayName("summary가 NULL이고 narrative_source가 NONE인 브리핑이 저장된다")
	void briefingRowWithNullSummaryAndNoneSourceIsPersisted() {
		Long id = marketBriefingRepository.saveAndFlush(MarketBriefing.create(
			Market.STOCK, ORIGIN_TRADE_DATE, null, NarrativeSource.NONE, GENERATED_AT)).getId();

		MarketBriefing found = marketBriefingRepository.findById(id).orElseThrow();

		assertThat(found.getSummary()).isNull();
		assertThat(found.getNarrativeSource()).isEqualTo(NarrativeSource.NONE);
		Map<String, Object> row = jdbcTemplate.queryForMap(
			"select summary, narrative_source from market_briefings where id = ?", id);
		assertThat(row.get("summary")).isNull();
		assertThat(row.get("narrative_source")).isEqualTo("NONE");
	}


	@Test
	@DisplayName("저장한 브리핑을 다시 읽으면 시장·거래일·생성 시각이 그대로 복원된다")
	void savedBriefingRoundTripsAllFields() {
		Long id = marketBriefingRepository.saveAndFlush(newBriefing(Market.CRYPTO, ORIGIN_TRADE_DATE)).getId();

		MarketBriefing found = marketBriefingRepository.findById(id).orElseThrow();

		assertThat(found.getMarket()).isEqualTo(Market.CRYPTO);
		assertThat(found.getOriginTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
		assertThat(found.getSummary()).isEqualTo(SUMMARY);
		assertThat(found.getGeneratedAt()).isEqualTo(GENERATED_AT);
	}

	@Test
	@DisplayName("market과 narrative_source가 이름 문자열로 저장된다")
	void enumColumnsStoreTheirNamesAsStrings() {
		marketBriefingRepository.saveAndFlush(newBriefing(Market.CRYPTO, ORIGIN_TRADE_DATE));

		Map<String, Object> row = jdbcTemplate.queryForMap("select market, narrative_source from market_briefings");

		assertThat(row.get("market")).isEqualTo("CRYPTO");
		assertThat(row.get("narrative_source")).isEqualTo("LLM");
	}

	@Test
	@DisplayName("summary는 varchar(255)를 넘는 브리핑도 잘리지 않고 그대로 복원된다")
	void summaryColumnKeepsTextLongerThanTwoHundredFiftyFiveCharacters() {
		String longSummary = "간밤 미국 증시가 강세로 마감했습니다. ".repeat(30);
		assertThat(longSummary.length()).isGreaterThan(255);

		Long id = marketBriefingRepository.saveAndFlush(MarketBriefing.create(
			Market.STOCK, ORIGIN_TRADE_DATE, longSummary, NarrativeSource.LLM, GENERATED_AT)).getId();

		assertThat(marketBriefingRepository.findById(id).orElseThrow().getSummary()).isEqualTo(longSummary);
	}

	@Test
	@DisplayName("origin_trade_date를 비우면 저장 자체가 실패한다")
	void originTradeDateCannotBeNull() {
		MarketBriefing withoutDate = MarketBriefing.create(
			Market.CRYPTO, null, SUMMARY, NarrativeSource.LLM, GENERATED_AT);

		assertThatThrownBy(() -> marketBriefingRepository.saveAndFlush(withoutDate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
