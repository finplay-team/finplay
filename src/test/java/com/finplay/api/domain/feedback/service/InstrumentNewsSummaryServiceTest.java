package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.store.FeedbackQueryCache;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class InstrumentNewsSummaryServiceTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 6, 8, 45);

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDateTime NEWS_FROM = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 30));
	private static final LocalDateTime PRE_MARKET_TO = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 0));
	private static final LocalDateTime FULL_TO = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 30));

	private static final int MAX_ITEMS_PER_SUMMARY = 5;

	private final MarketNewsItemRepository marketNewsItemRepository = mock(MarketNewsItemRepository.class);

	private final InstrumentNewsSummaryRepository instrumentNewsSummaryRepository = mock(
		InstrumentNewsSummaryRepository.class);

	private final NarrativeService narrativeService = mock(NarrativeService.class);

	private final Instrument instrument = stock();

	private final FeedbackQueryCache feedbackQueryCache = mock(FeedbackQueryCache.class);

	private final InstrumentNewsSummaryService service = new InstrumentNewsSummaryService(
		marketNewsItemRepository,
		instrumentNewsSummaryRepository,
		narrativeService,
		new BusinessDayCalendar(),
		feedbackQueryCache,
		new FeedbackNewsProperties(
			"0 0/30 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, 5, 50, 30, MAX_ITEMS_PER_SUMMARY),
		Clock.fixed(GENERATED_AT.atZone(KST).toInstant(), KST));

	private static Instrument stock() {
		Instrument created = Instrument.create(
			Market.STOCK, "SUMS01", "테스트종목", BigDecimal.ONE, 10000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(created, "id", 7L);
		return created;
	}

	private static MarketNewsItem item(long id, MarketNewsItemType type, LocalDateTime publishedAt) {
		MarketNewsItem news = MarketNewsItem.create(
			stock(), type, type + "-" + id, "테스트경제", "https://news.example.test/" + id, publishedAt, publishedAt);
		ReflectionTestUtils.setField(news, "id", id);
		return news;
	}

	private static MarketNewsItem news(long id, LocalTime publishedAt) {
		return item(id, MarketNewsItemType.NEWS, LocalDateTime.of(PREVIOUS_TRADE_DATE, publishedAt));
	}

	private static MarketNewsItem disclosureOn(long id, LocalDate receivedDate) {
		return item(id, MarketNewsItemType.DISCLOSURE, receivedDate.atStartOfDay());
	}

	private void givenNews(List<MarketNewsItem> items) {
		when(marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			anyLong(), any(), any(), any())).thenReturn(items);
	}

	private void givenDisclosuresOn(LocalDate receivedDate, List<MarketNewsItem> items) {
		when(marketNewsItemRepository.findDisclosuresReceivedOn(
			anyLong(), eq(receivedDate.atStartOfDay()), eq(receivedDate.plusDays(1).atStartOfDay())))
			.thenReturn(items);
	}

	private void givenNoDuplicateAndTemplateNarrative() {
		when(instrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope(any(), any(), any()))
			.thenReturn(false);
		when(narrativeService.resolveNewsSummaryNarrative(any()))
			.thenReturn(NarrativeResultDto.llm("전일 저녁 기사가 이어졌습니다."));
		when(instrumentNewsSummaryRepository.save(any()))
			.thenAnswer(invocation -> invocation.getArgument(0));
	}

	private NewsSummaryPromptDto capturedPrompt() {
		ArgumentCaptor<NewsSummaryPromptDto> captor = ArgumentCaptor.forClass(NewsSummaryPromptDto.class);
		verify(narrativeService).resolveNewsSummaryNarrative(captor.capture());
		return captor.getValue();
	}

	private InstrumentNewsSummary capturedSavedSummary() {
		ArgumentCaptor<InstrumentNewsSummary> captor = ArgumentCaptor.forClass(InstrumentNewsSummary.class);
		verify(instrumentNewsSummaryRepository).save(captor.capture());
		return captor.getValue();
	}

	@Nested
	@DisplayName("구간과 공시 날짜 판정 (§C-2·§C-3)")
	class ScopeWindow {

		@Test
		@DisplayName("PRE_MARKET은 뉴스를 [D-1 15:30, D 09:00]으로 조회한다")
		void queriesPreMarketNewsBetweenPreviousCloseAndMarketOpen() {
			givenNoDuplicateAndTemplateNarrative();
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			service.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);

			verify(marketNewsItemRepository).findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrument.getId(), MarketNewsItemType.NEWS, NEWS_FROM, PRE_MARKET_TO);
		}

		@Test
		@DisplayName("FULL은 하한이 같고 상한만 D 15:30까지 넓다")
		void queriesFullNewsWithTheSameLowerBoundAndTheCloseAsUpperBound() {
			givenNoDuplicateAndTemplateNarrative();
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			service.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL);

			verify(marketNewsItemRepository).findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				instrument.getId(), MarketNewsItemType.NEWS, NEWS_FROM, FULL_TO);
		}

		@Test
		@DisplayName("PRE_MARKET은 D-1 접수 공시만 합류시키고 D 접수분은 묻지 않는다")
		void joinsOnlyPreviousDayDisclosuresForPreMarket() {
			givenNoDuplicateAndTemplateNarrative();
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			service.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);

			verify(marketNewsItemRepository).findDisclosuresReceivedOn(
				instrument.getId(), PREVIOUS_TRADE_DATE.atStartOfDay(),
				PREVIOUS_TRADE_DATE.plusDays(1).atStartOfDay());
			verify(marketNewsItemRepository, never()).findDisclosuresReceivedOn(
				anyLong(), eq(ORIGIN_TRADE_DATE.atStartOfDay()), any());
		}

		@Test
		@DisplayName("FULL은 D-1과 D 접수 공시를 모두 합류시킨다")
		void joinsBothPreviousAndOriginDayDisclosuresForFull() {
			givenNoDuplicateAndTemplateNarrative();
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));
			givenDisclosuresOn(PREVIOUS_TRADE_DATE, List.of(disclosureOn(101L, PREVIOUS_TRADE_DATE)));
			givenDisclosuresOn(ORIGIN_TRADE_DATE, List.of(disclosureOn(102L, ORIGIN_TRADE_DATE)));

			service.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL);

			assertThat(capturedPrompt().items())
				.extracting(NewsSourceDto::disclosure)
				.filteredOn(disclosure -> disclosure)
				.hasSize(2);
		}

		@Test
		@DisplayName("ROLLING_24H로 주식 요약을 만들려 하면 즉시 실패한다")
		void rejectsRollingScopeOnTheStockPath() {
			when(instrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope(any(), any(), any()))
				.thenReturn(false);

			assertThatThrownBy(
				() -> service.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.ROLLING_24H))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ROLLING_24H");

			verifyNoInteractions(narrativeService);
		}
	}

	@Nested
	@DisplayName("LLM 입력 상한과 절단 (§C-7·§뉴스 매칭 범위)")
	class PromptLimit {

		private void givenTenNewsAndTwoDisclosures() {
			List<MarketNewsItem> newsItems = new ArrayList<>();
			for (int index = 0; index < 10; index++) {
				newsItems.add(news(index + 1L, LocalTime.of(16, 0).plusMinutes(index * 10L)));
			}
			givenNews(newsItems);
			givenDisclosuresOn(PREVIOUS_TRADE_DATE, List.of(
				disclosureOn(101L, PREVIOUS_TRADE_DATE), disclosureOn(102L, PREVIOUS_TRADE_DATE)));
		}

		@Test
		@DisplayName("프롬프트 기사 수가 max-items-per-summary를 넘지 않는다")
		void neverPutsMoreItemsInThePromptThanTheConfiguredLimit() {
			givenNoDuplicateAndTemplateNarrative();
			givenTenNewsAndTwoDisclosures();

			service.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);

			assertThat(capturedPrompt().items()).hasSize(MAX_ITEMS_PER_SUMMARY);
		}

		@Test
		@DisplayName("상한을 넘어도 D-1 접수 공시 2건이 프롬프트에 남는다")
		void keepsEveryDisclosureInThePromptEvenWhenOverTheLimit() {
			givenNoDuplicateAndTemplateNarrative();
			givenTenNewsAndTwoDisclosures();

			service.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);

			List<NewsSourceDto> items = capturedPrompt().items();
			assertThat(items).filteredOn(NewsSourceDto::disclosure).hasSize(2);
			assertThat(items).filteredOn(item -> !item.disclosure()).hasSize(3);
			assertThat(items).extracting(NewsSourceDto::publishedAt)
				.isSortedAccordingTo(java.util.Comparator.reverseOrder());
		}

		@Test
		@DisplayName("프롬프트가 범위와 종목명을 갖는다")
		void putsTheScopeAndInstrumentNameIntoThePrompt() {
			givenNoDuplicateAndTemplateNarrative();
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			service.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);

			NewsSummaryPromptDto prompt = capturedPrompt();
			assertThat(prompt.scope()).isEqualTo(NewsSummaryScope.PRE_MARKET);
			assertThat(prompt.instrumentName()).isEqualTo(instrument.getName());
			assertThat(prompt.referenceDate()).isEqualTo(ORIGIN_TRADE_DATE);
		}
	}

	@Nested
	@DisplayName("저장 규칙 (배치 ⑤·§C-4·§C-8)")
	class Persistence {

		@Test
		@DisplayName("이미 같은 (종목, 거래일, 범위) 요약이 있으면 LLM도 조회도 하지 않고 건너뛴다")
		void skipsWithoutQueryingOrCallingTheLlmWhenTheSummaryAlreadyExists() {
			when(instrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope(
				instrument.getId(), ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET)).thenReturn(true);

			Optional<InstrumentNewsSummary> result = service.generateStockSummary(instrument, ORIGIN_TRADE_DATE,
				NewsSummaryScope.PRE_MARKET);

			assertThat(result).isEmpty();
			verifyNoInteractions(narrativeService);
			verifyNoInteractions(marketNewsItemRepository);
			verify(instrumentNewsSummaryRepository, never()).save(any());
		}

		@Test
		@DisplayName("대상 기사가 0건이면 LLM을 부르지 않고 행도 만들지 않는다")
		void createsNoRowAndCallsNoLlmWhenThereAreNoItems() {
			when(instrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope(any(), any(), any()))
				.thenReturn(false);
			givenNews(List.of());

			Optional<InstrumentNewsSummary> result = service.generateStockSummary(instrument, ORIGIN_TRADE_DATE,
				NewsSummaryScope.PRE_MARKET);

			assertThat(result).isEmpty();
			verifyNoInteractions(narrativeService);
			verify(instrumentNewsSummaryRepository, never()).save(any());
		}

		@Test
		@DisplayName("서술이 NONE이어도 summary가 null인 행을 남긴다")
		void persistsARowWithNullSummaryWhenTheNarrativeIsNone() {
			when(instrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope(any(), any(), any()))
				.thenReturn(false);
			when(instrumentNewsSummaryRepository.save(any())).thenAnswer(call -> call.getArgument(0));
			when(narrativeService.resolveNewsSummaryNarrative(any())).thenReturn(NarrativeResultDto.none());
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			Optional<InstrumentNewsSummary> result = service.generateStockSummary(instrument, ORIGIN_TRADE_DATE,
				NewsSummaryScope.PRE_MARKET);

			assertThat(result).isPresent();
			InstrumentNewsSummary saved = capturedSavedSummary();
			assertThat(saved.getSummary()).isNull();
			assertThat(saved.getNarrativeSource()).isEqualTo(NarrativeSource.NONE);
		}

		@Test
		@DisplayName("저장 행이 종목·거래일·범위와 고정 Clock의 generatedAt을 갖는다")
		void persistsTheRowWithTheGivenKeysAndTheFixedClockTimestamp() {
			givenNoDuplicateAndTemplateNarrative();
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			service.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL);

			InstrumentNewsSummary saved = capturedSavedSummary();
			assertThat(saved.getInstrument().getId()).isEqualTo(instrument.getId());
			assertThat(saved.getOriginTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
			assertThat(saved.getScope()).isEqualTo(NewsSummaryScope.FULL);
			assertThat(saved.getNarrativeSource()).isEqualTo(NarrativeSource.LLM);
			assertThat(saved.getGeneratedAt()).isEqualTo(GENERATED_AT);
		}

		@Test
		@DisplayName("주식 경로는 기존 행이 있어도 갱신하지 않고 건너뛴다 — refreshNarrative는 코인 전용이다")
		void neverRefreshesAnExistingRowOnTheStockPath() {
			when(instrumentNewsSummaryRepository.existsByInstrumentIdAndOriginTradeDateAndScope(
				any(), any(), any())).thenReturn(true);

			service.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);

			verify(instrumentNewsSummaryRepository, never()).save(any());
			verify(instrumentNewsSummaryRepository, never())
				.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(any(), any());
		}
	}

	@Nested
	@DisplayName("코인 갱신 (배치 ⑩·⑬·§C-9)")
	class CryptoRefresh {

		private final Instrument coin = crypto();

		private static Instrument crypto() {
			Instrument created = Instrument.create(
				Market.CRYPTO, "SUMBTC", "비트코인", BigDecimal.ONE, 5000L, true, LocalDateTime.now());
			ReflectionTestUtils.setField(created, "id", 9L);
			return created;
		}

		private void givenNoPreviousRow() {
			when(instrumentNewsSummaryRepository.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(
				any(), any())).thenReturn(Optional.empty());
			when(instrumentNewsSummaryRepository.findByInstrumentIdAndOriginTradeDateAndScope(
				any(), any(), any())).thenReturn(Optional.empty());
			when(narrativeService.resolveNewsSummaryNarrative(any()))
				.thenReturn(NarrativeResultDto.llm("최근 24시간 기사가 이어졌습니다."));
			when(instrumentNewsSummaryRepository.save(any())).thenAnswer(call -> call.getArgument(0));
		}

		private InstrumentNewsSummary previousRow(LocalDateTime generatedAt) {
			return InstrumentNewsSummary.create(
				coin, generatedAt.toLocalDate(), NewsSummaryScope.ROLLING_24H, "직전 요약",
				NarrativeSource.LLM, generatedAt);
		}

		@Test
		@DisplayName("직전 생성 이후 수집된 기사가 없으면 LLM을 부르지 않고 저장도 하지 않는다")
		void skipsWithoutCallingTheLlmWhenNothingWasCollectedSinceTheLastGeneration() {
			LocalDateTime lastGeneratedAt = GENERATED_AT.minusHours(1);
			when(instrumentNewsSummaryRepository.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(
				coin.getId(), NewsSummaryScope.ROLLING_24H))
				.thenReturn(Optional.of(previousRow(lastGeneratedAt)));
			when(marketNewsItemRepository.existsByInstrumentIdAndCreatedAtAfter(
				coin.getId(), lastGeneratedAt)).thenReturn(false);

			assertThat(service.refreshCryptoSummary(coin)).isEmpty();

			verifyNoInteractions(narrativeService);
			verify(instrumentNewsSummaryRepository, never()).save(any());
		}

		@Test
		@DisplayName("재생성 판정을 created_at 기준 파인더로 한다 — 직전 생성 시각을 그대로 넘긴다")
		void decidesRegenerationByCollectedAtNotPublishedAt() {
			LocalDateTime lastGeneratedAt = GENERATED_AT.minusHours(1);
			when(instrumentNewsSummaryRepository.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(
				any(), any())).thenReturn(Optional.of(previousRow(lastGeneratedAt)));
			when(marketNewsItemRepository.existsByInstrumentIdAndCreatedAtAfter(any(), any()))
				.thenReturn(true);
			when(instrumentNewsSummaryRepository.findByInstrumentIdAndOriginTradeDateAndScope(
				any(), any(), any())).thenReturn(Optional.empty());
			when(narrativeService.resolveNewsSummaryNarrative(any()))
				.thenReturn(NarrativeResultDto.llm("최근 24시간 기사가 이어졌습니다."));
			when(instrumentNewsSummaryRepository.save(any())).thenAnswer(call -> call.getArgument(0));
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			service.refreshCryptoSummary(coin);

			verify(marketNewsItemRepository)
				.existsByInstrumentIdAndCreatedAtAfter(coin.getId(), lastGeneratedAt);
		}

		@Test
		@DisplayName("직전 생성 행이 없으면 재생성 판정 없이 만든다")
		void generatesWithoutTheRegenerationCheckOnTheFirstRun() {
			givenNoPreviousRow();
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			assertThat(service.refreshCryptoSummary(coin)).isPresent();

			verify(marketNewsItemRepository, never())
				.existsByInstrumentIdAndCreatedAtAfter(any(), any());
		}

		@Test
		@DisplayName("범위가 ROLLING_24H 하나뿐이고 공시를 묻지 않는다")
		void usesOnlyTheRollingScopeAndNeverAsksForDisclosures() {
			givenNoPreviousRow();
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			service.refreshCryptoSummary(coin);

			InstrumentNewsSummary saved = capturedSavedSummary();
			assertThat(saved.getScope()).isEqualTo(NewsSummaryScope.ROLLING_24H);
			verify(marketNewsItemRepository, never()).findDisclosuresReceivedOn(any(), any(), any());
		}

		@Test
		@DisplayName("최근 24시간 창으로 기사를 모은다")
		void collectsArticlesFromTheLastTwentyFourHours() {
			givenNoPreviousRow();
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			service.refreshCryptoSummary(coin);

			verify(marketNewsItemRepository).findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				coin.getId(), MarketNewsItemType.NEWS, GENERATED_AT.minusHours(24), GENERATED_AT);
		}

		@Test
		@DisplayName("origin_trade_date가 배치 실행 시점의 KST 날짜다")
		void storesTheBatchRunDateAsOriginTradeDate() {
			givenNoPreviousRow();
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			service.refreshCryptoSummary(coin);

			assertThat(capturedSavedSummary().getOriginTradeDate())
				.isEqualTo(GENERATED_AT.toLocalDate());
		}

		@Test
		@DisplayName("같은 날 행이 이미 있으면 새 행을 만들지 않고 그 행을 갱신한다")
		void updatesTheExistingRowOfTheSameDayInsteadOfInsertingANewOne() {
			LocalDateTime lastGeneratedAt = GENERATED_AT.minusHours(1);
			InstrumentNewsSummary existing = previousRow(lastGeneratedAt);
			when(instrumentNewsSummaryRepository.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(
				any(), any())).thenReturn(Optional.of(existing));
			when(marketNewsItemRepository.existsByInstrumentIdAndCreatedAtAfter(any(), any()))
				.thenReturn(true);
			when(instrumentNewsSummaryRepository.findByInstrumentIdAndOriginTradeDateAndScope(
				coin.getId(), GENERATED_AT.toLocalDate(), NewsSummaryScope.ROLLING_24H))
				.thenReturn(Optional.of(existing));
			when(narrativeService.resolveNewsSummaryNarrative(any()))
				.thenReturn(NarrativeResultDto.llm("새 요약입니다."));
			when(instrumentNewsSummaryRepository.save(any())).thenAnswer(call -> call.getArgument(0));
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			service.refreshCryptoSummary(coin);

			InstrumentNewsSummary saved = capturedSavedSummary();
			assertThat(saved).isSameAs(existing);
			assertThat(saved.getSummary()).isEqualTo("새 요약입니다.");
			assertThat(saved.getGeneratedAt()).isEqualTo(GENERATED_AT);
			assertThat(saved.getOriginTradeDate()).isEqualTo(lastGeneratedAt.toLocalDate());
			assertThat(saved.getScope()).isEqualTo(NewsSummaryScope.ROLLING_24H);
		}

		@Test
		@DisplayName("최근 24시간 기사가 0건이면 LLM을 부르지 않고 행도 만들지 않는다")
		void createsNoRowWhenTheRollingWindowIsEmpty() {
			givenNoPreviousRow();
			givenNews(List.of());

			assertThat(service.refreshCryptoSummary(coin)).isEmpty();

			verifyNoInteractions(narrativeService);
			verify(instrumentNewsSummaryRepository, never()).save(any());
		}


		@Test
		@DisplayName("갱신에 성공하면 그 종목의 조회 캐시를 지운다")
		void evictsTheQueryCacheOfThatInstrumentWhenTheRefreshActuallyStored() {
			givenNoPreviousRow();
			givenNews(List.of(news(1L, LocalTime.of(18, 0))));

			assertThat(service.refreshCryptoSummary(coin)).isPresent();

			verify(feedbackQueryCache).evictCryptoSummaryText(coin.getId());
		}

		@Test
		@DisplayName("직전 생성 이후 새 기사가 없어 건너뛴 실행은 캐시를 지우지 않는다")
		void doesNotEvictWhenTheRefreshWasSkippedBecauseNothingWasCollected() {
			LocalDateTime lastGeneratedAt = GENERATED_AT.minusHours(1);
			when(instrumentNewsSummaryRepository.findFirstByInstrumentIdAndScopeOrderByGeneratedAtDescIdDesc(
				any(), any())).thenReturn(Optional.of(previousRow(lastGeneratedAt)));
			when(marketNewsItemRepository.existsByInstrumentIdAndCreatedAtAfter(any(), any())).thenReturn(false);

			assertThat(service.refreshCryptoSummary(coin)).isEmpty();

			verifyNoInteractions(feedbackQueryCache);
		}

		@Test
		@DisplayName("창 안 기사가 0건이라 만들지 않은 실행도 캐시를 지우지 않는다")
		void doesNotEvictWhenNoRowWasCreatedBecauseTheWindowWasEmpty() {
			givenNoPreviousRow();
			givenNews(List.of());

			assertThat(service.refreshCryptoSummary(coin)).isEmpty();

			verifyNoInteractions(feedbackQueryCache);
		}
	}

	@Test
	@DisplayName("주식 요약 생성 경로는 조회 캐시를 한 번도 건드리지 않는다")
	void stockSummaryGenerationNeverTouchesTheQueryCache() {
		givenNoDuplicateAndTemplateNarrative();
		givenNews(List.of(news(1L, LocalTime.of(18, 0))));

		service.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);

		verifyNoInteractions(feedbackQueryCache);
	}
}
