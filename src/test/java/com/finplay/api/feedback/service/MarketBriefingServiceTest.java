// 개장 전 브리핑 확정(전장 구간·시장 단일 질의·상한·중복·NONE 행)을 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.domain.MarketBriefing;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.repository.MarketBriefingRepository;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

// 값의 정본은 spec.md다 — 구간은 §C-2(전장 [D-1 15:30, D 09:00]), 공시는 §C-3(rcept_dt = D-1),
// 상한은 §C-7의 max-items-per-summary(프롬프트 입력이라 응답 상한이 아니다), 절단은 §뉴스 매칭 범위다.
class MarketBriefingServiceTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 6, 8, 45);

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDateTime PRE_MARKET_FROM = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 30));
	private static final LocalDateTime PRE_MARKET_TO = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 0));

	// 상한을 5로 낮춰 픽스처를 그 위로 잡는다 — 상한 아래면 절단 규칙의 유무가 구분되지 않는다.
	private static final int MAX_ITEMS_PER_SUMMARY = 5;

	private final MarketNewsItemRepository marketNewsItemRepository = mock(MarketNewsItemRepository.class);

	private final MarketBriefingRepository marketBriefingRepository = mock(MarketBriefingRepository.class);

	private final NarrativeService narrativeService = mock(NarrativeService.class);

	private final MarketBriefingService service = new MarketBriefingService(
		marketNewsItemRepository,
		marketBriefingRepository,
		narrativeService,
		new BusinessDayCalendar(),
		new FeedbackNewsProperties(
			"0 0/30 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, 5, 50, 30, MAX_ITEMS_PER_SUMMARY),
		Clock.fixed(GENERATED_AT.atZone(KST).toInstant(), KST));

	private static Instrument stock(String symbol, String name) {
		Instrument created = Instrument.create(
			Market.STOCK, symbol, name, BigDecimal.ONE, 10000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(created, "id", (long)symbol.hashCode());
		return created;
	}

	private static MarketNewsItem item(
		long id, Instrument instrument, MarketNewsItemType type, LocalDateTime publishedAt) {
		MarketNewsItem news = MarketNewsItem.create(
			instrument, type, type + "-" + id, "테스트경제", "https://news.example.test/" + id, publishedAt,
			publishedAt);
		ReflectionTestUtils.setField(news, "id", id);
		return news;
	}

	private static MarketNewsItem news(long id, String name, LocalTime publishedAt) {
		return item(id, stock("BR" + id, name), MarketNewsItemType.NEWS,
			LocalDateTime.of(PREVIOUS_TRADE_DATE, publishedAt));
	}

	private static MarketNewsItem disclosure(long id, String name) {
		return item(id, stock("BD" + id, name), MarketNewsItemType.DISCLOSURE,
			PREVIOUS_TRADE_DATE.atStartOfDay());
	}

	private void givenNoDuplicateAndNarrative() {
		when(marketBriefingRepository.existsByMarketAndOriginTradeDate(any(), any())).thenReturn(false);
		when(narrativeService.resolveMarketBriefingNarrative(any()))
			.thenReturn(NarrativeResultDto.llm("전일 저녁부터 개장 전까지 기사가 이어졌습니다."));
		when(marketBriefingRepository.save(any())).thenAnswer(call -> call.getArgument(0));
	}

	private void givenMarketNews(List<MarketNewsItem> items) {
		when(marketNewsItemRepository.findMarketNewsPublishedBetween(any(), any(), any())).thenReturn(items);
	}

	private void givenMarketDisclosures(List<MarketNewsItem> items) {
		when(marketNewsItemRepository.findMarketDisclosuresReceivedOn(any(), any(), any())).thenReturn(items);
	}

	private MarketBriefingPromptDto capturedPrompt() {
		ArgumentCaptor<MarketBriefingPromptDto> captor = ArgumentCaptor.forClass(MarketBriefingPromptDto.class);
		verify(narrativeService).resolveMarketBriefingNarrative(captor.capture());
		return captor.getValue();
	}

	// 브리핑은 전장 구간만 본다 — 상한이 09:00을 넘으면 장중 기사가 아침 브리핑에 들어간다(FEED-009,
	// 완료 조건 게이트 ⑪). 하한이 Part C 요약과 같은 D-1 15:30인 것이 게이트 ⑦의 근거다.
	@Test
	@DisplayName("전장 구간 [D-1 15:30, D 09:00]으로 시장 전체 뉴스를 조회한다")
	void queriesMarketWideNewsWithinThePreMarketWindow() {
		givenNoDuplicateAndNarrative();
		givenMarketNews(List.of(news(1L, "테스트종목A", LocalTime.of(18, 0))));

		service.generateStockBriefing(ORIGIN_TRADE_DATE);

		verify(marketNewsItemRepository)
			.findMarketNewsPublishedBetween(Market.STOCK, PRE_MARKET_FROM, PRE_MARKET_TO);
	}

	// 종목별로 나눠 물으면 상한이 종목당으로 걸려 전체가 상한의 몇 배로 불어난다 (§C-2-1).
	@Test
	@DisplayName("종목별 파인더를 쓰지 않고 시장 단일 질의로 모은다")
	void neverFallsBackToThePerInstrumentFinders() {
		givenNoDuplicateAndNarrative();
		givenMarketNews(List.of(news(1L, "테스트종목A", LocalTime.of(18, 0))));

		service.generateStockBriefing(ORIGIN_TRADE_DATE);

		verify(marketNewsItemRepository, never())
			.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(any(), any(), any(), any());
		verify(marketNewsItemRepository, never()).findDisclosuresReceivedOn(any(), any(), any());
	}

	// D 접수 공시가 들어오면 개장 전 브리핑이 그날 장중 접수분을 알려준다 (§C-3, 게이트 ⑫).
	@Test
	@DisplayName("공시는 D-1 접수분 하루만 합류시킨다")
	void joinsOnlyThePreviousDayDisclosures() {
		givenNoDuplicateAndNarrative();
		givenMarketNews(List.of(news(1L, "테스트종목A", LocalTime.of(18, 0))));

		service.generateStockBriefing(ORIGIN_TRADE_DATE);

		verify(marketNewsItemRepository).findMarketDisclosuresReceivedOn(
			Market.STOCK, PREVIOUS_TRADE_DATE.atStartOfDay(), PREVIOUS_TRADE_DATE.plusDays(1).atStartOfDay());
		verify(marketNewsItemRepository, never()).findMarketDisclosuresReceivedOn(
			any(), eq(ORIGIN_TRADE_DATE.atStartOfDay()), any());
	}

	// 브리핑은 전 종목 합산 단일 목록이라 종목당 2건만 쌓여도 상한을 넘는다 — 공시가 사실상 상시 전멸하는
	// 자리가 여기다 (§뉴스 매칭 범위).
	@Test
	@DisplayName("상한을 넘어도 공시가 프롬프트에 남고 전체 건수는 상한을 넘지 않는다")
	void keepsDisclosuresInThePromptEvenWhenTheMarketWideListIsOverTheLimit() {
		givenNoDuplicateAndNarrative();
		// 간격을 분으로 잡는다 — LocalTime은 자정을 넘으면 되감겨 뒤쪽 기사가 공시 시각(00:00)과 동률이 된다.
		List<MarketNewsItem> newsItems = new ArrayList<>();
		for (int index = 0; index < 10; index++) {
			newsItems.add(news(index + 1L, "테스트종목" + index, LocalTime.of(16, 0).plusMinutes(index * 10L)));
		}
		givenMarketNews(newsItems);
		givenMarketDisclosures(List.of(disclosure(101L, "공시종목A"), disclosure(102L, "공시종목B")));

		service.generateStockBriefing(ORIGIN_TRADE_DATE);

		List<BriefingNewsItemDto> items = capturedPrompt().items();
		assertThat(items).hasSize(MAX_ITEMS_PER_SUMMARY);
		assertThat(items).filteredOn(item -> item.source().disclosure()).hasSize(2);
		assertThat(items).extracting(item -> item.source().publishedAt())
			.isSortedAccordingTo(java.util.Comparator.reverseOrder());
	}

	// 시장 단일 목록이라 어느 종목 소식인지 모델이 알 수 없다 — 종목명이 빠지면 브리핑 문장이 종목을 잃는다.
	@Test
	@DisplayName("프롬프트의 기사마다 종목명이 붙는다")
	void attachesTheInstrumentNameToEveryPromptItem() {
		givenNoDuplicateAndNarrative();
		givenMarketNews(List.of(news(1L, "테스트종목A", LocalTime.of(18, 0))));
		givenMarketDisclosures(List.of(disclosure(101L, "공시종목B")));

		service.generateStockBriefing(ORIGIN_TRADE_DATE);

		MarketBriefingPromptDto prompt = capturedPrompt();
		assertThat(prompt.market()).isEqualTo(Market.STOCK);
		assertThat(prompt.referenceDate()).isEqualTo(ORIGIN_TRADE_DATE);
		assertThat(prompt.items())
			.extracting(BriefingNewsItemDto::instrumentName)
			.containsExactlyInAnyOrder("테스트종목A", "공시종목B");
	}

	@Test
	@DisplayName("이미 그 거래일 브리핑이 있으면 LLM도 조회도 하지 않고 건너뛴다")
	void skipsWithoutQueryingOrCallingTheLlmWhenTheBriefingAlreadyExists() {
		when(marketBriefingRepository.existsByMarketAndOriginTradeDate(Market.STOCK, ORIGIN_TRADE_DATE))
			.thenReturn(true);

		Optional<MarketBriefing> result = service.generateStockBriefing(ORIGIN_TRADE_DATE);

		assertThat(result).isEmpty();
		verifyNoInteractions(narrativeService);
		verifyNoInteractions(marketNewsItemRepository);
		verify(marketBriefingRepository, never()).save(any());
	}

	// 배포 직후 이틀은 근거 구간이 비어 있을 수 있다 — 정상 동작이며 오류가 아니다(FEED-009).
	// §C-4 판정 순서 3번(기사 0건 → EMPTY)이 행 유무보다 먼저라 행을 만들지 않아도 조회 결과가 같다.
	@Test
	@DisplayName("전장 구간 기사가 0건이면 LLM을 부르지 않고 행도 만들지 않는다")
	void createsNoRowAndCallsNoLlmWhenThePreMarketWindowIsEmpty() {
		when(marketBriefingRepository.existsByMarketAndOriginTradeDate(any(), any())).thenReturn(false);
		givenMarketNews(List.of());
		givenMarketDisclosures(List.of());

		Optional<MarketBriefing> result = service.generateStockBriefing(ORIGIN_TRADE_DATE);

		assertThat(result).isEmpty();
		verifyNoInteractions(narrativeService);
		verify(marketBriefingRepository, never()).save(any());
	}

	@Test
	@DisplayName("서술이 NONE이어도 summary가 null인 행을 남긴다")
	void persistsARowWithNullSummaryWhenTheNarrativeIsNone() {
		when(marketBriefingRepository.existsByMarketAndOriginTradeDate(any(), any())).thenReturn(false);
		when(marketBriefingRepository.save(any())).thenAnswer(call -> call.getArgument(0));
		when(narrativeService.resolveMarketBriefingNarrative(any())).thenReturn(NarrativeResultDto.none());
		givenMarketNews(List.of(news(1L, "테스트종목A", LocalTime.of(18, 0))));

		assertThat(service.generateStockBriefing(ORIGIN_TRADE_DATE)).isPresent();

		ArgumentCaptor<MarketBriefing> captor = ArgumentCaptor.forClass(MarketBriefing.class);
		verify(marketBriefingRepository).save(captor.capture());
		assertThat(captor.getValue().getSummary()).isNull();
		assertThat(captor.getValue().getNarrativeSource()).isEqualTo(NarrativeSource.NONE);
		assertThat(captor.getValue().getMarket()).isEqualTo(Market.STOCK);
		assertThat(captor.getValue().getOriginTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
		assertThat(captor.getValue().getGeneratedAt()).isEqualTo(GENERATED_AT);
	}
}
