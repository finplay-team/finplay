package com.finplay.api.domain.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.domain.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.service.InstrumentNewsSummaryService;
import com.finplay.api.domain.feedback.service.NarrativeResultDto;
import com.finplay.api.domain.feedback.service.NarrativeService;
import com.finplay.api.domain.market.entity.Market;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = "feedback.query-cache.enabled=true")
class FeedbackQueryCacheEvictionIntegrationTest extends FeedbackQueryCacheWiringSupport {

	private static final String FIRST_TEXT = "첫 배치가 만든 서술입니다.";

	private static final String SECOND_TEXT = "두 번째 배치가 만든 새 서술입니다.";

	private static final LocalTime LAST_GENERATED_AT = LocalTime.of(8, 30);

	private static final LocalTime COLLECTED_BEFORE_LAST_RUN = LocalTime.of(8, 0);

	private static final LocalTime COLLECTED_AFTER_LAST_RUN = LocalTime.of(9, 0);

	@Autowired
	private InstrumentNewsSummaryService instrumentNewsSummaryService;

	@MockitoBean
	private NarrativeService narrativeService;

	@BeforeEach
	void givenTheBatchProducesTheFirstText() {
		when(narrativeService.resolveNewsSummaryNarrative(any()))
			.thenReturn(NarrativeResultDto.llm(FIRST_TEXT));
		when(narrativeService.resolveMarketBriefingNarrative(any()))
			.thenReturn(NarrativeResultDto.llm(FIRST_TEXT));
	}


	private void saveCryptoNewsCollectedAt(String title, LocalTime publishedAt, LocalTime collectedAt) {
		marketNewsItemRepository.save(MarketNewsItem.create(
			crypto, MarketNewsItemType.NEWS, title, "테스트경제",
			"https://news.example.test/cache/crypto/" + title,
			LocalDateTime.of(SERVICE_DATE, publishedAt),
			LocalDateTime.of(SERVICE_DATE, collectedAt)));
	}

	private void saveCryptoSummaryGeneratedAt(String text, LocalTime generatedAt) {
		instrumentNewsSummaryRepository.save(InstrumentNewsSummary.create(
			crypto, SERVICE_DATE, NewsSummaryScope.ROLLING_24H, text, NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, generatedAt)));
	}

	private void saveCryptoBriefingGeneratedAt(String text, LocalTime generatedAt) {
		marketBriefingRepository.save(MarketBriefing.create(
			Market.CRYPTO, SERVICE_DATE, text, NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, generatedAt)));
	}

	private InstrumentNewsResponse queryCryptoSummary() {
		return instrumentNewsQueryService.getInstrumentNews(crypto.getId());
	}

	private MarketBriefingResponse queryCryptoBriefing() {
		return marketBriefingService.getBriefing(Market.CRYPTO);
	}


	@Test
	@DisplayName("코인 요약: 조회 → 배치 갱신 → 재조회에서 새 서술이 나온다")
	void cryptoSummaryQueryAfterASuccessfulRefreshSeesTheNewText() {
		saveCryptoNewsCollectedAt("코인 기사", LocalTime.of(9, 0), COLLECTED_BEFORE_LAST_RUN);
		saveCryptoSummaryGeneratedAt(FIRST_TEXT, LAST_GENERATED_AT);
		assertThat(queryCryptoSummary().summary()).isEqualTo(FIRST_TEXT);

		saveCryptoNewsCollectedAt("코인 후속 기사", LocalTime.of(9, 30), COLLECTED_AFTER_LAST_RUN);
		when(narrativeService.resolveNewsSummaryNarrative(any()))
			.thenReturn(NarrativeResultDto.llm(SECOND_TEXT));
		assertThat(instrumentNewsSummaryService.refreshCryptoSummary(crypto)).isPresent();

		assertThat(queryCryptoSummary().summary())
			.as("무효화가 없으면 다음 정시 05분까지 옛 서술이 그대로 나간다")
			.isEqualTo(SECOND_TEXT);
	}

	@Test
	@DisplayName("코인 브리핑: 조회 → 배치 갱신 → 재조회에서 새 서술이 나온다")
	void cryptoBriefingQueryAfterASuccessfulRefreshSeesTheNewText() {
		saveCryptoNewsCollectedAt("코인 기사", LocalTime.of(9, 0), COLLECTED_BEFORE_LAST_RUN);
		saveCryptoBriefingGeneratedAt(FIRST_TEXT, LAST_GENERATED_AT);
		assertThat(queryCryptoBriefing().summary()).isEqualTo(FIRST_TEXT);

		saveCryptoNewsCollectedAt("코인 후속 기사", LocalTime.of(9, 30), COLLECTED_AFTER_LAST_RUN);
		when(narrativeService.resolveMarketBriefingNarrative(any()))
			.thenReturn(NarrativeResultDto.llm(SECOND_TEXT));
		assertThat(marketBriefingService.refreshCryptoBriefing()).isPresent();

		assertThat(queryCryptoBriefing().summary()).isEqualTo(SECOND_TEXT);
	}


	@Test
	@DisplayName("코인 요약: 새 기사가 없어 건너뛴 배치 뒤에도 캐시가 남아 원본이 다시 불리지 않는다")
	void cryptoSummaryCacheSurvivesABatchRunThatSkippedTheRefresh() {
		saveCryptoNewsCollectedAt("코인 기사", LocalTime.of(9, 0), COLLECTED_BEFORE_LAST_RUN);
		saveCryptoSummaryGeneratedAt(FIRST_TEXT, LAST_GENERATED_AT);
		assertThat(queryCryptoSummary().summary()).isEqualTo(FIRST_TEXT);

		assertThat(instrumentNewsSummaryService.refreshCryptoSummary(crypto)).isEmpty();
		clearInvocations(instrumentNewsSummaryRepository);

		assertThat(queryCryptoSummary().summary()).isEqualTo(FIRST_TEXT);
		verify(instrumentNewsSummaryRepository, never())
			.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(any(), any());
	}

	@Test
	@DisplayName("코인 브리핑: 새 기사가 없어 건너뛴 배치 뒤에도 캐시가 남아 원본이 다시 불리지 않는다")
	void cryptoBriefingCacheSurvivesABatchRunThatSkippedTheRefresh() {
		saveCryptoNewsCollectedAt("코인 기사", LocalTime.of(9, 0), COLLECTED_BEFORE_LAST_RUN);
		saveCryptoBriefingGeneratedAt(FIRST_TEXT, LAST_GENERATED_AT);
		assertThat(queryCryptoBriefing().summary()).isEqualTo(FIRST_TEXT);

		assertThat(marketBriefingService.refreshCryptoBriefing()).isEmpty();
		clearInvocations(marketBriefingRepository);

		assertThat(queryCryptoBriefing().summary()).isEqualTo(FIRST_TEXT);
		verify(marketBriefingRepository, never()).findFirstByMarketOrderByGeneratedAtDescIdDesc(any());
	}


	@Test
	@DisplayName("요약 행이 없는 상태로 조회한 뒤 배치가 행을 만들면 그다음 조회가 새 값을 본다")
	void queryAfterANegativeResultSeesTheRowTheBatchCreatesLater() {
		saveCryptoNewsCollectedAt("코인 기사", LocalTime.of(9, 0), COLLECTED_AFTER_LAST_RUN);

		InstrumentNewsResponse beforeBatch = queryCryptoSummary();
		assertThat(beforeBatch.summaryStatus()).isEqualTo(FeedbackContentStatus.EMPTY);
		assertThat(beforeBatch.summary()).isNull();

		assertThat(instrumentNewsSummaryService.refreshCryptoSummary(crypto)).isPresent();

		InstrumentNewsResponse afterBatch = queryCryptoSummary();
		assertThat(afterBatch.summaryStatus()).isEqualTo(FeedbackContentStatus.READY);
		assertThat(afterBatch.summary()).isEqualTo(FIRST_TEXT);
	}

	@Test
	@DisplayName("브리핑 행이 없는 상태로 조회한 뒤 배치가 행을 만들면 그다음 조회가 새 값을 본다")
	void briefingQueryAfterANegativeResultSeesTheRowTheBatchCreatesLater() {
		saveCryptoNewsCollectedAt("코인 기사", LocalTime.of(9, 0), COLLECTED_AFTER_LAST_RUN);

		MarketBriefingResponse beforeBatch = queryCryptoBriefing();
		assertThat(beforeBatch.status()).isEqualTo(FeedbackContentStatus.EMPTY);
		assertThat(beforeBatch.summary()).isNull();

		assertThat(marketBriefingService.refreshCryptoBriefing()).isPresent();

		MarketBriefingResponse afterBatch = queryCryptoBriefing();
		assertThat(afterBatch.status()).isEqualTo(FeedbackContentStatus.READY);
		assertThat(afterBatch.summary()).isEqualTo(FIRST_TEXT);
	}


	@Test
	@DisplayName("주식 브리핑 생성 경로가 돈 뒤에도 주식 조회 캐시는 그대로 남는다")
	void stockBriefingGenerationLeavesTheStockQueryCacheIntact() {
		saveStockNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveStockBriefing("간밤 기사가 이어졌습니다.");
		marketBriefingService.getBriefing(Market.STOCK);

		assertThat(marketBriefingService.generateStockBriefing(ORIGIN_TRADE_DATE)).isEmpty();
		clearInvocations(marketNewsItemRepository, marketBriefingRepository);

		assertThat(marketBriefingService.getBriefing(Market.STOCK).summary())
			.isEqualTo("간밤 기사가 이어졌습니다.");
		verify(marketBriefingRepository, never()).findByMarketAndOriginTradeDate(any(), any());
		verify(marketNewsItemRepository, never()).findMarketNewsPublishedBetween(any(), any(), any());
	}
}
