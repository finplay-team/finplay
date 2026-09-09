package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.domain.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.domain.feedback.repository.MarketBriefingRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.store.FeedbackQueryCacheTestKeys;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	TestClockConfig.class})
class CryptoFeedbackBatchIntegrationTest {

	private static final LocalDate DAY_ONE = LocalDate.of(2026, 8, 6);
	private static final LocalDate DAY_TWO = LocalDate.of(2026, 8, 7);
	private static final LocalDateTime LATE_NIGHT_RUN = LocalDateTime.of(DAY_ONE, LocalTime.of(23, 5));
	private static final LocalDateTime AFTER_MIDNIGHT_RUN = LocalDateTime.of(DAY_TWO, LocalTime.of(0, 5));

	private static final String CRYPTO_SYMBOL = "BTC";

	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	private static final List<String> READ_ONLY_TABLES = List.of("instruments", "market_news_items");

	private static final List<String> OTHER_FEEDBACK_TABLES = List.of("price_move_events",
		"price_move_event_sources", "trade_feedbacks", "price_move_peer_stats");

	@Autowired
	private CryptoFeedbackBatchService cryptoFeedbackBatchService;

	@Autowired
	private InstrumentNewsQueryService instrumentNewsQueryService;

	@Autowired
	private MarketBriefingService marketBriefingService;

	@Autowired
	private InstrumentService instrumentService;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private InstrumentNewsSummaryRepository instrumentNewsSummaryRepository;

	@Autowired
	private MarketBriefingRepository marketBriefingRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private NarrativeService narrativeService;

	@Autowired
	private TestClock clock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private TestClock mutableClock;

	private Instrument coin;

	@BeforeEach
	void clearQueryCache() {
		FeedbackQueryCacheTestKeys.clear(redisTemplate);
	}

	@BeforeEach
	void setUp() {
		mutableClock = clock;
		mutableClock.set(LATE_NIGHT_RUN);
		coin = instrumentService.getInstrumentEntities(Market.CRYPTO).stream()
			.filter(each -> CRYPTO_SYMBOL.equals(each.getSymbol()))
			.findFirst()
			.orElseThrow();
		when(narrativeService.resolveNewsSummaryNarrative(any()))
			.thenReturn(NarrativeResultDto.llm("최근 24시간 기사가 이어졌습니다."));
		when(narrativeService.resolveMarketBriefingNarrative(any()))
			.thenReturn(NarrativeResultDto.llm("최근 24시간 코인 기사가 이어졌습니다."));
	}

	private void saveNews(String title, LocalDateTime publishedAt, LocalDateTime collectedAt) {
		marketNewsItemRepository.save(MarketNewsItem.create(
			coin,
			MarketNewsItemType.NEWS,
			title,
			"테스트경제",
			"https://news.example.test/crypto/" + title,
			publishedAt,
			collectedAt));
	}

	private List<InstrumentNewsSummary> summaries() {
		return instrumentNewsSummaryRepository.findAll().stream()
			.filter(row -> row.getInstrument().getId().equals(coin.getId()))
			.toList();
	}

	private List<MarketBriefing> cryptoBriefings() {
		return marketBriefingRepository.findAll().stream()
			.filter(row -> row.getMarket() == Market.CRYPTO)
			.toList();
	}

	private Map<String, Long> rowCounts(List<String> tables) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : tables) {
			counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		return counts;
	}


	@Test
	@DisplayName("자정을 넘겨도 새 기사가 없으면 LLM을 부르지 않고, 그때도 조회는 어제 요약으로 채워진다")
	void skipsTheLlmAfterMidnightWhileTheQueryStillServesYesterdaysSummary() {
		saveNews("어제 저녁 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(22, 0)), LocalDateTime.of(DAY_ONE, LocalTime.of(22, 30)));

		cryptoFeedbackBatchService.refreshCryptoFeedback();
		verify(narrativeService, times(1)).resolveNewsSummaryNarrative(any());
		assertThat(summaries()).singleElement()
			.extracting(InstrumentNewsSummary::getOriginTradeDate).isEqualTo(DAY_ONE);

		mutableClock.set(AFTER_MIDNIGHT_RUN);
		cryptoFeedbackBatchService.refreshCryptoFeedback();

		verify(narrativeService, times(1)).resolveNewsSummaryNarrative(any());
		assertThat(summaries()).hasSize(1);
		assertThat(summaries()).singleElement()
			.extracting(InstrumentNewsSummary::getOriginTradeDate).isEqualTo(DAY_ONE);

		InstrumentNewsResponse response = instrumentNewsQueryService.getInstrumentNews(coin.getId());
		assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.READY);
		assertThat(response.summary()).isEqualTo("최근 24시간 기사가 이어졌습니다.");
		assertThat(response.summaryScope()).isEqualTo(NewsSummaryScope.ROLLING_24H);
	}

	@Test
	@DisplayName("자정 직후에도 코인 브리핑 조회가 어제 만든 행을 그대로 준다")
	void keepsServingYesterdaysCryptoBriefingRightAfterMidnight() {
		saveNews("어제 저녁 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(22, 0)), LocalDateTime.of(DAY_ONE, LocalTime.of(22, 30)));
		cryptoFeedbackBatchService.refreshCryptoFeedback();

		mutableClock.set(AFTER_MIDNIGHT_RUN);
		cryptoFeedbackBatchService.refreshCryptoFeedback();

		verify(narrativeService, times(1)).resolveMarketBriefingNarrative(any());
		MarketBriefingResponse response = marketBriefingService.getBriefing(Market.CRYPTO);
		assertThat(response.status()).isEqualTo(FeedbackContentStatus.READY);
		assertThat(response.summary()).isEqualTo("최근 24시간 코인 기사가 이어졌습니다.");
		assertThat(response.originTradeDate()).isNull();
	}

	@Test
	@DisplayName("발행은 이르고 수집만 늦은 기사도 다음 배치에서 정확히 한 번 반영된다")
	void regeneratesForAnArticlePublishedEarlyButCollectedLate() {
		saveNews("첫 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(22, 0)), LocalDateTime.of(DAY_ONE, LocalTime.of(22, 30)));
		cryptoFeedbackBatchService.refreshCryptoFeedback();
		verify(narrativeService, times(1)).resolveNewsSummaryNarrative(any());

		saveNews("늦게 수집된 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(23, 3)), LocalDateTime.of(DAY_ONE, LocalTime.of(23, 30)));
		mutableClock.set(LocalDateTime.of(DAY_ONE, LocalTime.of(23, 59)));
		cryptoFeedbackBatchService.refreshCryptoFeedback();

		verify(narrativeService, times(2)).resolveNewsSummaryNarrative(any());
	}


	@Test
	@DisplayName("같은 날 여러 번 돌아도 요약·브리핑이 하루 1행이고 generated_at만 갱신된다")
	void keepsExactlyOneRowPerDayWhileUpdatingGeneratedAt() {
		saveNews("22시 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(22, 0)), LocalDateTime.of(DAY_ONE, LocalTime.of(22, 30)));
		cryptoFeedbackBatchService.refreshCryptoFeedback();
		Long firstSummaryId = summaries().get(0).getId();
		Long firstBriefingId = cryptoBriefings().get(0).getId();

		saveNews("23시 30분 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(23, 30)), LocalDateTime.of(DAY_ONE, LocalTime.of(23, 40)));
		mutableClock.set(LocalDateTime.of(DAY_ONE, LocalTime.of(23, 59)));
		cryptoFeedbackBatchService.refreshCryptoFeedback();

		assertThat(summaries()).hasSize(1);
		assertThat(cryptoBriefings()).hasSize(1);
		assertThat(summaries().get(0).getId()).isEqualTo(firstSummaryId);
		assertThat(cryptoBriefings().get(0).getId()).isEqualTo(firstBriefingId);
		assertThat(summaries().get(0).getGeneratedAt())
			.isEqualTo(LocalDateTime.of(DAY_ONE, LocalTime.of(23, 59)));
		assertThat(summaries().get(0).getOriginTradeDate()).isEqualTo(DAY_ONE);
	}

	@Test
	@DisplayName("날짜가 바뀌고 새 기사가 있으면 그날의 행이 새로 생기고 어제 행은 남는다")
	void createsANewRowForTheNextDayWithoutRemovingYesterdays() {
		saveNews("어제 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(22, 0)), LocalDateTime.of(DAY_ONE, LocalTime.of(22, 30)));
		cryptoFeedbackBatchService.refreshCryptoFeedback();

		saveNews("자정 이후 기사",
			LocalDateTime.of(DAY_TWO, LocalTime.of(0, 1)), LocalDateTime.of(DAY_TWO, LocalTime.of(0, 2)));
		mutableClock.set(AFTER_MIDNIGHT_RUN);
		cryptoFeedbackBatchService.refreshCryptoFeedback();

		assertThat(summaries()).hasSize(2);
		assertThat(summaries()).extracting(InstrumentNewsSummary::getOriginTradeDate)
			.containsExactlyInAnyOrder(DAY_ONE, DAY_TWO);
		assertThat(instrumentNewsQueryService.getInstrumentNews(coin.getId()).summaryStatus())
			.isEqualTo(FeedbackContentStatus.READY);
	}


	@Test
	@DisplayName("코인 배치가 ROLLING_24H 행만 만들고 주식의 두 범위를 쓰지 않는다")
	void writesOnlyRollingScopeRows() {
		saveNews("22시 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(22, 0)), LocalDateTime.of(DAY_ONE, LocalTime.of(22, 30)));

		cryptoFeedbackBatchService.refreshCryptoFeedback();

		assertThat(summaries()).isNotEmpty();
		assertThat(summaries()).extracting(InstrumentNewsSummary::getScope)
			.containsOnly(NewsSummaryScope.ROLLING_24H);
	}


	@Test
	@DisplayName("조회를 반복해도 LLM 호출이 늘지 않고 행도 늘지 않는다")
	void neverCallsTheLlmOrWritesWhileQueryingRepeatedly() {
		saveNews("22시 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(22, 0)), LocalDateTime.of(DAY_ONE, LocalTime.of(22, 30)));
		cryptoFeedbackBatchService.refreshCryptoFeedback();
		long summaryRowsAfterBatch = instrumentNewsSummaryRepository.count();
		long briefingRowsAfterBatch = marketBriefingRepository.count();

		for (int attempt = 0; attempt < 3; attempt++) {
			instrumentNewsQueryService.getInstrumentNews(coin.getId());
			marketBriefingService.getBriefing(Market.CRYPTO);
		}

		verify(narrativeService, times(1)).resolveNewsSummaryNarrative(any());
		verify(narrativeService, times(1)).resolveMarketBriefingNarrative(any());
		assertThat(instrumentNewsSummaryRepository.count()).isEqualTo(summaryRowsAfterBatch);
		assertThat(marketBriefingRepository.count()).isEqualTo(briefingRowsAfterBatch);
	}


	@Test
	@DisplayName("코인 배치가 요약·브리핑 두 테이블 밖에 쓰지 않는다")
	void neverWritesOutsideTheTwoCryptoOutputTables() {
		saveNews("22시 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(22, 0)), LocalDateTime.of(DAY_ONE, LocalTime.of(22, 30)));
		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		Map<String, Long> readOnlyBefore = rowCounts(READ_ONLY_TABLES);
		Map<String, Long> otherFeedbackBefore = rowCounts(OTHER_FEEDBACK_TABLES);

		cryptoFeedbackBatchService.refreshCryptoFeedback();

		assertThat(summaries()).isNotEmpty();
		assertThat(cryptoBriefings()).isNotEmpty();
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
		assertThat(rowCounts(OTHER_FEEDBACK_TABLES)).isEqualTo(otherFeedbackBefore);
	}

}
