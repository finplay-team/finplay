// 종목 뉴스 요약 확정(구간·공시 날짜 판정·상한·중복·NONE 행)을 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

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

import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.InstrumentNewsSummary;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.feedback.repository.InstrumentNewsSummaryRepository;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.BusinessDayCalendar;
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

// 값의 정본은 spec.md다 — 구간은 §C-2, 공시 날짜 판정은 §C-3, 상한은 §C-7, 절단은 §뉴스 매칭 범위,
// 상태값 판정 순서는 §C-4다.
//
// 유니크 축과 summary NULL 저장은 InstrumentNewsSummaryRepositoryTest가 실제 MySQL로 맡고,
// 배치 종단은 FeedbackBatchIntegrationTest가 맡는다. 여기서는 "무엇을 조회하고 무엇을 저장하는가"를 본다.
class InstrumentNewsSummaryServiceTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 6, 8, 45);

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// §C-2의 전장·전일 구간 경계. 벽시계이며 분봉 시각이 아니다 (§C-2-1).
	private static final LocalDateTime NEWS_FROM = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 30));
	private static final LocalDateTime PRE_MARKET_TO = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 0));
	private static final LocalDateTime FULL_TO = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 30));

	// 상한을 5로 낮춰 둔다 — 픽스처를 상한 위로 잡아야 절단 규칙의 유무가 구분된다 (§뉴스 매칭 범위).
	private static final int MAX_ITEMS_PER_SUMMARY = 5;

	private final MarketNewsItemRepository marketNewsItemRepository = mock(MarketNewsItemRepository.class);

	private final InstrumentNewsSummaryRepository instrumentNewsSummaryRepository = mock(
		InstrumentNewsSummaryRepository.class);

	private final NarrativeService narrativeService = mock(NarrativeService.class);

	private final Instrument instrument = stock();

	private final InstrumentNewsSummaryService service = new InstrumentNewsSummaryService(
		marketNewsItemRepository,
		instrumentNewsSummaryRepository,
		narrativeService,
		new BusinessDayCalendar(),
		// 앞 다섯 값은 이 경로가 쓰지 않는다 — 마지막 max-items-per-summary만 걸린다 (§C-7).
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

		// 하한이 D-1 15:30이 아니면 전장 구간 17.5시간의 일부가 통째로 빠진다. 상한이 09:00을 넘으면
		// 개장 전 요약에 장중 기사가 새어 나간다 (FEED-008).
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

		// D 접수 공시가 PRE_MARKET에 섞이면 개장 전 요약이 그날 장중 접수분을 알려준다 (§C-3).
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

		// 코인은 구간도 저장 규칙도 다르다 (§C-2·§C-9). 조용히 주식 구간으로 만들면 재생 시간축이 없는 종목에
		// 원본 거래일 구간이 붙어 매일 0건이 되고 예외도 로그도 남지 않는다.
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

		// 픽스처가 상한(5) 위여야 절단이 실제로 일어난다. 상한 아래면 "공시 우선"을 통째로 지워도 초록이다.
		private void givenTenNewsAndTwoDisclosures() {
			// 간격을 분으로 잡는다 — LocalTime은 자정을 넘으면 되감겨 뒤쪽 기사가 공시 시각(00:00)과
			// 동률이 되고 "공시가 최하위"라는 이 규칙의 전제가 흐려진다.
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

		// 이 자리가 §뉴스 매칭 범위의 "세 자리" 중 하나다 — 공시가 잘리면 게이트 ⑫가 요약에서 깨진다.
		@Test
		@DisplayName("상한을 넘어도 D-1 접수 공시 2건이 프롬프트에 남는다")
		void keepsEveryDisclosureInThePromptEvenWhenOverTheLimit() {
			givenNoDuplicateAndTemplateNarrative();
			givenTenNewsAndTwoDisclosures();

			service.generateStockSummary(instrument, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);

			List<NewsSourceDto> items = capturedPrompt().items();
			assertThat(items).filteredOn(NewsSourceDto::disclosure).hasSize(2);
			assertThat(items).filteredOn(item -> !item.disclosure()).hasSize(3);
			// 순서는 절단과 별개다 — 발행시각 내림차순이라 공시가 맨 아래다.
			assertThat(items).extracting(NewsSourceDto::publishedAt)
				.isSortedAccordingTo(java.util.Comparator.reverseOrder());
		}

		// 프롬프트에 범위를 넣지 않으면 PRE_MARKET 생성에 FULL 기사를 넣는 실수를 모델 쪽에서 잡을 수 없다.
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

		// 중복 확인을 서술보다 뒤로 미루면 재실행 때마다 종목 수만큼 LLM을 다시 부르고 결과는 유니크가 버린다.
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

		// §C-4 판정 순서 3번이 "대상 기사·공시 0건 → EMPTY"를 행 유무와 무관하게 먼저 읽는다. 그래서 행을
		// 만들어도 화면이 달라지지 않고 근거 없는 문장을 LLM이 지어낼 자리만 생긴다. 4번(행 없음)도 EMPTY라
		// 두 경로의 결과가 같다는 것이 이 판단의 근거다.
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

		// 이것이 위와 갈리는 자리다 — 기사가 있는데 서술만 실패한 상태는 행으로 남겨야 조회가 UNAVAILABLE을
		// 낼 수 있다. 행을 만들지 않으면 EMPTY와 구분되지 않는다 (§C-4 4·5번).
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
	}
}
