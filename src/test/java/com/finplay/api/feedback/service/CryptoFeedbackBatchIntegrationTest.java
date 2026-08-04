// 가변 Clock + 실 MySQL로 코인 매시 배치의 재생성 판정·UPSERT·최신 1행 조회와 원장 불변을 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.domain.FeedbackContentStatus;
import com.finplay.api.feedback.domain.InstrumentNewsSummary;
import com.finplay.api.feedback.domain.MarketBriefing;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.feedback.repository.MarketBriefingRepository;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

// 완료 조건 배치 ⑨~⑬이 이 파일의 목표다. 오케스트레이션(격리·대상)은 CryptoFeedbackBatchServiceTest가,
// 판정과 저장 인자는 각 서비스의 단위 테스트가 맡는다.
//
// 여기서만 볼 수 있는 것은 셋이다 — UPSERT가 실제로 하루 1행을 유지하는지(유니크 제약 위에서), 자정을
// 넘겨도 조회가 비지 않는지, 그리고 반복 실행·반복 조회에서 LLM 호출 수가 어떻게 변하는지다.
//
// NarrativeService를 mock으로 두는 것은 호출 횟수를 세기 위해서다. 실제 LLM은 어차피 부르지 않지만
// (api-key가 not-configured) 호출 횟수는 이 mock으로만 셀 수 있다.
//
// 공유 컨테이너를 더럽히지 않도록 클래스 트랜잭션으로 감싼다 (PriceMoveQueryGateIntegrationTest 선례).
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class,
	CryptoFeedbackBatchIntegrationTest.MutableClockTestConfig.class})
class CryptoFeedbackBatchIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// 배치가 23:05에 한 번 돌고, 자정을 넘긴 다음 00:05에 다시 도는 시나리오가 이 파일의 축이다.
	private static final LocalDate DAY_ONE = LocalDate.of(2026, 8, 6);
	private static final LocalDate DAY_TWO = LocalDate.of(2026, 8, 7);
	private static final LocalDateTime LATE_NIGHT_RUN = LocalDateTime.of(DAY_ONE, LocalTime.of(23, 5));
	private static final LocalDateTime AFTER_MIDNIGHT_RUN = LocalDateTime.of(DAY_TWO, LocalTime.of(0, 5));

	private static final String CRYPTO_SYMBOL = "BTC";

	// 배치 실행 전후로 행이 변하면 안 되는 원장 테이블
	private static final List<String> LEDGER_TABLES = List.of("orders", "trades", "accounts", "holdings",
		"holding_lots", "trade_allocations");

	// 코인 배치가 읽기만 해야 하는 테이블
	private static final List<String> READ_ONLY_TABLES = List.of("instruments", "market_news_items");

	// 코인 배치가 건드리지 않아야 하는 다른 피드백 테이블 (카드·매도 회고·집단 비교는 다른 경로 소유다)
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
	private Clock clock;

	private MutableClock mutableClock;

	private Instrument coin;

	@BeforeEach
	void setUp() {
		mutableClock = (MutableClock)clock;
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

	// --- 배치 ⑩·⑪ 자정 직후 (같은 설계의 앞뒷면) ---

	// "직전 생성"을 오늘 행이 아니라 generated_at 최신 행으로 잡은 판단을 실제로 재현한다. 오늘 행 기준이면
	// 날짜가 바뀌었다는 이유만으로 새 기사 없이도 LLM을 한 번 부른다 — 내용이 같은 요약을 다시 만드는 것이다.
	//
	// 두 조건이 동시에 성립해야 그 설계가 성립한다. ⑩만 지키고 ⑪이 깨지면 자정~00:05에 화면이 비고,
	// ⑪만 지키고 ⑩이 깨지면 매일 자정마다 전 종목 LLM 호출이 한 번씩 늘어난다.
	@Test
	@DisplayName("자정을 넘겨도 새 기사가 없으면 LLM을 부르지 않고, 그때도 조회는 어제 요약으로 채워진다")
	void skipsTheLlmAfterMidnightWhileTheQueryStillServesYesterdaysSummary() {
		saveNews("어제 저녁 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(22, 0)), LocalDateTime.of(DAY_ONE, LocalTime.of(22, 30)));

		cryptoFeedbackBatchService.refreshCryptoFeedback();
		verify(narrativeService, times(1)).resolveNewsSummaryNarrative(any());
		assertThat(summaries()).singleElement()
			.extracting(InstrumentNewsSummary::getOriginTradeDate).isEqualTo(DAY_ONE);

		// 자정을 넘겨 다시 돈다. 그 사이 수집된 기사는 없다.
		mutableClock.set(AFTER_MIDNIGHT_RUN);
		cryptoFeedbackBatchService.refreshCryptoFeedback();

		// 배치 ⑩ — 새 기사가 없으니 LLM 호출이 늘지 않고 오늘 행도 생기지 않는다.
		verify(narrativeService, times(1)).resolveNewsSummaryNarrative(any());
		assertThat(summaries()).hasSize(1);
		assertThat(summaries()).singleElement()
			.extracting(InstrumentNewsSummary::getOriginTradeDate).isEqualTo(DAY_ONE);

		// 배치 ⑪ — 그런데도 조회는 비지 않는다. "오늘 날짜 행"으로 찾았다면 여기서 EMPTY가 된다.
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
		// 저장된 행의 origin_trade_date는 배치 실행 날짜라 응답에 내리지 않는다 (§C-9).
		assertThat(response.originTradeDate()).isNull();
	}

	// 판정 기준이 published_at이면 이 기사는 영원히 요약에 못 들어간다 — 발행은 직전 생성보다 이른데
	// 수집만 늦기 때문이다. created_at 기준이면 정확히 한 번 잡힌다.
	@Test
	@DisplayName("발행은 이르고 수집만 늦은 기사도 다음 배치에서 정확히 한 번 반영된다")
	void regeneratesForAnArticlePublishedEarlyButCollectedLate() {
		saveNews("첫 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(22, 0)), LocalDateTime.of(DAY_ONE, LocalTime.of(22, 30)));
		cryptoFeedbackBatchService.refreshCryptoFeedback();
		verify(narrativeService, times(1)).resolveNewsSummaryNarrative(any());

		// 23:03 발행인데 23:30에 수집됐다 — 직전 생성(23:05)보다 발행은 이르고 수집은 늦다.
		saveNews("늦게 수집된 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(23, 3)), LocalDateTime.of(DAY_ONE, LocalTime.of(23, 30)));
		mutableClock.set(LocalDateTime.of(DAY_ONE, LocalTime.of(23, 59)));
		cryptoFeedbackBatchService.refreshCryptoFeedback();

		verify(narrativeService, times(2)).resolveNewsSummaryNarrative(any());
	}

	// --- 배치 ⑫ UPSERT로 하루 1행 ---

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
		// 새 행이 아니라 같은 행이다 — 새로 만들면 유니크에 걸려 그 시각 갱신이 통째로 실패한다.
		assertThat(summaries().get(0).getId()).isEqualTo(firstSummaryId);
		assertThat(cryptoBriefings().get(0).getId()).isEqualTo(firstBriefingId);
		assertThat(summaries().get(0).getGeneratedAt())
			.isEqualTo(LocalDateTime.of(DAY_ONE, LocalTime.of(23, 59)));
		assertThat(summaries().get(0).getOriginTradeDate()).isEqualTo(DAY_ONE);
	}

	// 날짜가 바뀌고 새 기사도 있으면 그때는 새 행이다 — 하루 1행이지 전체 1행이 아니다.
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
		// 조회는 그중 generated_at 최신 행을 본다.
		assertThat(instrumentNewsQueryService.getInstrumentNews(coin.getId()).summaryStatus())
			.isEqualTo(FeedbackContentStatus.READY);
	}

	// --- 배치 ⑬ 한 범위만 쓴다 ---

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

	// --- 배치 ⑨ 조회는 생성하지 않는다 ---

	// GET이 LLM을 부르면 갱신 직후 동시 요청이 전부 호출하고 그 순간의 첫 사용자가 최대 40초를 기다린다.
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

	// --- 공통 조건 원장 불변 (코인 배치에서도 재확인한다) ---

	@Test
	@DisplayName("코인 배치가 요약·브리핑 두 테이블 밖에 쓰지 않는다")
	void neverWritesOutsideTheTwoCryptoOutputTables() {
		saveNews("22시 기사",
			LocalDateTime.of(DAY_ONE, LocalTime.of(22, 0)), LocalDateTime.of(DAY_ONE, LocalTime.of(22, 30)));
		Map<String, Long> ledgerBefore = rowCounts(LEDGER_TABLES);
		Map<String, Long> readOnlyBefore = rowCounts(READ_ONLY_TABLES);
		Map<String, Long> otherFeedbackBefore = rowCounts(OTHER_FEEDBACK_TABLES);

		cryptoFeedbackBatchService.refreshCryptoFeedback();

		// 배치가 실제로 쓰기를 했는데도 나머지가 그대로여야 의미가 있다.
		assertThat(summaries()).isNotEmpty();
		assertThat(cryptoBriefings()).isNotEmpty();
		assertThat(rowCounts(LEDGER_TABLES)).isEqualTo(ledgerBefore);
		assertThat(rowCounts(READ_ONLY_TABLES)).isEqualTo(readOnlyBefore);
		assertThat(rowCounts(OTHER_FEEDBACK_TABLES)).isEqualTo(otherFeedbackBefore);
	}

	@TestConfiguration
	static class MutableClockTestConfig {

		@Bean
		@Primary
		Clock mutableClock() {
			return new MutableClock(LATE_NIGHT_RUN.atZone(KST).toInstant());
		}
	}

	// 배치를 여러 시각에서 돌려야 자정 경계와 UPSERT를 볼 수 있다.
	private static final class MutableClock extends Clock {

		private volatile Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void set(LocalDateTime localDateTime) {
			this.instant = localDateTime.atZone(KST).toInstant();
		}

		@Override
		public ZoneId getZone() {
			return KST;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
