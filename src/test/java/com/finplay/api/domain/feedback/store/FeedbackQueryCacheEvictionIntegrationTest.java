// 코인 배치가 실제로 갱신했을 때만 조회 캐시를 지우고, 건너뛴 실행 뒤에는 캐시가 남는지 실 Redis·MySQL로 검증한다 (tasks.md 항목 5, ADR-0015 §3).
package com.finplay.api.domain.feedback.store;

import com.finplay.api.domain.feedback.service.InstrumentNewsSummaryService;
import com.finplay.api.domain.feedback.service.NarrativeService;
import com.finplay.api.domain.feedback.service.NarrativeResultDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.domain.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.domain.market.entity.Market;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * <b>코인은 값이 있는 상태에서 바뀌는 유일한 경우다</b>(ADR-0015 §3). 주식은 한 번 생긴 값이 그날 안 바뀌므로
 * TTL만으로 충분하지만, 코인은 매시 배치가 같은 키의 값을 갈아치우므로 무효화가 없으면 다음 정시 05분까지
 * 옛 서술이 그대로 나간다.
 *
 * <p>대칭이 되는 반대편도 함께 본다 — <b>갱신하지 않은 실행이 캐시를 지우면 안 된다.</b> 지우면 값이 바뀌지도
 * 않았는데 다음 조회가 DB로 내려가 캐시를 둔 목적이 그만큼 깎인다. 두 단정을 한 클래스에 두는 이유는 한쪽만
 * 보면 "항상 지운다"와 "아무 때도 안 지운다"가 둘 다 통과하기 때문이다.
 *
 * <p><b>기사의 {@code created_at}과 직전 생성의 {@code generated_at}을 픽스처에서 직접 정한다.</b> 재생성 판정이
 * {@code created_at > generated_at}인데(§C-9) 이 클래스의 {@code Clock}은 고정이라, 두 값을 모두 "지금"으로 두면
 * 부등호가 성립하지 않아 <b>갱신하려는 시나리오까지 조용히 건너뛴다.</b> 실제로 처음 그렇게 썼다가 두 테스트가
 * "갱신했는데 값이 그대로"가 아니라 "갱신 자체가 안 됨"으로 깨졌다 — 시각을 명시하지 않으면 이 테스트가
 * 무효화가 아니라 재생성 판정을 검사하게 된다.
 *
 * <p>{@code NarrativeService}는 {@code @MockitoBean}이다 — 실 LLM을 부르지 않으면서 배치가 만들 서술을 테스트가
 * 정하기 위해서다. "재조회에서 <b>새 값</b>이 나온다"는 그 문장이 바뀌는 것으로만 확인된다.
 */
@TestPropertySource(properties = "feedback.query-cache.enabled=true")
class FeedbackQueryCacheEvictionIntegrationTest extends FeedbackQueryCacheWiringSupport {

	private static final String FIRST_TEXT = "첫 배치가 만든 서술입니다.";

	private static final String SECOND_TEXT = "두 번째 배치가 만든 새 서술입니다.";

	// 직전 생성 시각. 아래 기사들의 created_at이 이 시각의 앞/뒤 어디에 놓이느냐가 배치가 도는지를 정한다.
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

	// ── 픽스처 (created_at·generated_at을 명시한다) ──────────────────────────────────

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

	// ── 갱신에 성공하면 재조회가 새 값을 본다 ────────────────────────────────────────

	@Test
	@DisplayName("코인 요약: 조회 → 배치 갱신 → 재조회에서 새 서술이 나온다")
	void cryptoSummaryQueryAfterASuccessfulRefreshSeesTheNewText() {
		saveCryptoNewsCollectedAt("코인 기사", LocalTime.of(9, 0), COLLECTED_BEFORE_LAST_RUN);
		saveCryptoSummaryGeneratedAt(FIRST_TEXT, LAST_GENERATED_AT);
		assertThat(queryCryptoSummary().summary()).isEqualTo(FIRST_TEXT);

		// 직전 생성 이후 수집된 기사가 있어야 배치가 다시 돈다(§C-9의 created_at 기준 재생성 판정).
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

	// ── 건너뛴 배치 실행 뒤에는 캐시가 그대로 남는다 ─────────────────────────────────

	// 값이 안 바뀌었으므로 지울 이유가 없다. 지우면 다음 조회가 이유 없이 DB로 내려간다 — 그것을 "원본이 다시
	// 불리지 않는다"로 확인한다(응답만 보면 지웠든 안 지웠든 같은 문장이라 구분되지 않는다).
	@Test
	@DisplayName("코인 요약: 새 기사가 없어 건너뛴 배치 뒤에도 캐시가 남아 원본이 다시 불리지 않는다")
	void cryptoSummaryCacheSurvivesABatchRunThatSkippedTheRefresh() {
		saveCryptoNewsCollectedAt("코인 기사", LocalTime.of(9, 0), COLLECTED_BEFORE_LAST_RUN);
		saveCryptoSummaryGeneratedAt(FIRST_TEXT, LAST_GENERATED_AT);
		assertThat(queryCryptoSummary().summary()).isEqualTo(FIRST_TEXT);

		// 직전 생성 이후 수집된 기사가 없으므로 이 실행은 갱신 없이 빠져나간다.
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

	// ── 음성 결과 뒤에 배치가 행을 만들면 그다음 조회가 본다 ─────────────────────────

	// 음성 결과를 캐시하면 배치가 행을 만든 뒤에도 옛 "없음" 상태가 남는다 — 이 경우를 막는 장치는 무효화가
	// 아니라 "저장하지 않는다"다(ADR-0015 §3). 지웠기 때문이 아니라 애초에 아무것도 안 담겼기 때문에 통과해야
	// 하므로, 배치가 만든 첫 값이 곧바로 보이는지로 확인한다.
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

	// ── 주식은 무효화하지 않는다 ─────────────────────────────────────────────────────

	// 주식 브리핑 생성은 이미 있는 행을 건너뛰므로 값이 그날 안 바뀐다. 여기서 캐시를 지우면 매 배치가 그날의
	// 주식 캐시를 통째로 날려 조회가 다시 DB로 간다 — 지우지 않는 것이 결정이다(ADR-0015 §3).
	@Test
	@DisplayName("주식 브리핑 생성 경로가 돈 뒤에도 주식 조회 캐시는 그대로 남는다")
	void stockBriefingGenerationLeavesTheStockQueryCacheIntact() {
		saveStockNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveStockBriefing("간밤 기사가 이어졌습니다.");
		marketBriefingService.getBriefing(Market.STOCK);

		// 이미 그 거래일 행이 있으므로 생성은 건너뛴다 — 그래도 캐시를 건드리지 않는지가 요점이다.
		assertThat(marketBriefingService.generateStockBriefing(ORIGIN_TRADE_DATE)).isEmpty();
		clearInvocations(marketNewsItemRepository, marketBriefingRepository);

		assertThat(marketBriefingService.getBriefing(Market.STOCK).summary())
			.isEqualTo("간밤 기사가 이어졌습니다.");
		verify(marketBriefingRepository, never()).findByMarketAndOriginTradeDate(any(), any());
		verify(marketNewsItemRepository, never()).findMarketNewsPublishedBetween(any(), any(), any());
	}
}
