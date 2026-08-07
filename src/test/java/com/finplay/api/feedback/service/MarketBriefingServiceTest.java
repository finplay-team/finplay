// 개장 전 브리핑 확정(전장 구간·시장 단일 질의·상한·중복·NONE 행)을 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.feedback.domain.FeedbackContentStatus;
import com.finplay.api.feedback.domain.MarketBriefing;
import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.feedback.repository.MarketBriefingRepository;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.store.FeedbackQueryCache;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.BusinessDayCalendar;
import com.finplay.api.market.service.StockReplayService;
import com.finplay.api.market.service.StockReplaySessionDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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

// 값의 정본은 spec.md다 — 구간은 §C-2(전장 [D-1 15:30, D 09:00]), 공시는 §C-3(rcept_dt = D-1),
// 상한은 §C-7(생성은 max-items-per-summary, 조회는 max-items-per-briefing), 절단은 §뉴스 매칭 범위,
// 조회 상태값과 판정 순서는 §C-4다.
class MarketBriefingServiceTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 6, 8, 45);

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDateTime PRE_MARKET_FROM = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 30));
	private static final LocalDateTime PRE_MARKET_TO = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 0));

	// 상한을 낮춰 픽스처를 그 위로 잡는다 — 상한 아래면 절단 규칙의 유무가 구분되지 않는다.
	//
	// 두 값을 일부러 다르게 준다. 이 클래스 안에 생성(LLM 입력)과 조회(응답 items)의 상한이 공존하는데
	// (§C-7), 같은 값이면 두 자리를 바꿔 써도 테스트가 전부 초록이다. 값이 갈려야 뒤바뀜이 드러난다.
	private static final int MAX_ITEMS_PER_SUMMARY = 5;

	private static final int MAX_ITEMS_PER_BRIEFING = 3;

	private final MarketNewsItemRepository marketNewsItemRepository = mock(MarketNewsItemRepository.class);

	private final MarketBriefingRepository marketBriefingRepository = mock(MarketBriefingRepository.class);

	private final NarrativeService narrativeService = mock(NarrativeService.class);

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	// 뒤 세 값이 §C-7의 목록 상한 3종이고, 이 클래스가 쓰는 것은 briefing(조회)과 summary(생성) 둘이다.
	private final FeedbackNewsProperties properties = new FeedbackNewsProperties(
		"0 0/30 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, 5, 50, MAX_ITEMS_PER_BRIEFING,
		MAX_ITEMS_PER_SUMMARY);

	private final MarketBriefingService service = new MarketBriefingService(
		marketNewsItemRepository,
		marketBriefingRepository,
		stockReplayService,
		narrativeService,
		briefingReader(),
		loaderDirectCache(),
		properties,
		Clock.fixed(GENERATED_AT.atZone(KST).toInstant(), KST));

	// DB 읽기가 Reader로 나갔어도(트랜잭션 경계 분리, PR #257) 이 클래스가 보는 것은 그대로다 — 진짜 Reader에
	// 같은 mock 리포지토리를 그대로 물려 조립하므로 아래 스텁·verify가 한 줄도 바뀌지 않는다.
	private MarketBriefingReader briefingReader() {
		return new MarketBriefingReader(
			marketNewsItemRepository, marketBriefingRepository, new BusinessDayCalendar(), properties);
	}

	// 킬 스위치를 내린(enabled=false) FeedbackQueryCache다 — 항상 로더로 직행하므로 이 클래스의 판정 순서·
	// 상한 단정이 캐시 도입 전과 그대로 성립한다. Redis·JSON 협력자는 그 경로에서 한 번도 쓰이지 않아 null로
	// 두고, 키·TTL 재료(Clock·items 상한)만 실제 값을 준다 — 그 둘은 캐시가 꺼져 있어도 호출 시점에
	// 계산되기 때문이다(캐시가 켜진 동작은 FeedbackQueryCacheTest와 브리핑 조회 통합 테스트가 맡는다).
	private FeedbackQueryCache loaderDirectCache() {
		return new FeedbackQueryCache(
			null, null, null, Clock.system(KST), new FeedbackQueryCacheProperties(false, 1000, 300, 20), properties);
	}

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

	// 조회 경로는 생성과 같은 클래스에 있지만 판정도 상한도 다르다. 시각을 옮겨야 §C-4의 1·2번 경계를 볼 수
	// 있어 여기서만 MutableClock을 쓴 별도 인스턴스를 만든다 — 바깥 인스턴스의 고정 Clock(08:45)은
	// generatedAt 단정이 의존하고 있어 바꿀 수 없다.
	@Nested
	@DisplayName("조회 (§C-4 판정 순서·§C-7 응답 상한)")
	class Query {

		private final MutableClock clock = new MutableClock(
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 0)).atZone(KST).toInstant());

		private final MarketBriefingService queryService = new MarketBriefingService(
			marketNewsItemRepository,
			marketBriefingRepository,
			stockReplayService,
			narrativeService,
			briefingReader(),
			loaderDirectCache(),
			properties,
			clock);

		private void givenReadySession() {
			when(stockReplayService.getCurrentReplaySession())
				.thenReturn(new StockReplaySessionDto(true, ORIGIN_TRADE_DATE));
		}

		private void givenNotReadySession() {
			when(stockReplayService.getCurrentReplaySession())
				.thenReturn(new StockReplaySessionDto(false, null));
		}

		private void givenBriefingRow(Optional<MarketBriefing> row) {
			when(marketBriefingRepository.findByMarketAndOriginTradeDate(any(), any())).thenReturn(row);
		}

		private MarketBriefing briefingRow(String text) {
			return MarketBriefing.create(
				Market.STOCK,
				ORIGIN_TRADE_DATE,
				text,
				text == null ? NarrativeSource.NONE : NarrativeSource.LLM,
				GENERATED_AT);
		}

		private void at(LocalTime time) {
			clock.set(LocalDateTime.of(SERVICE_DATE, time));
		}

		// --- §C-4 판정 순서 ---

		// 1번이 EMPTY이고 2번이 NOT_YET인 것이 Part C와 갈리는 자리다. 08:00은 두 조건이 함께 성립하는
		// 시각이라 순서가 뒤바뀌면 여기서만 갈린다 — 2번이 먼저면 NOT_YET에 확정되지도 않은 날짜가 붙는다.
		@Test
		@DisplayName("세션 미준비 + 개장 전이면 EMPTY이고 originTradeDate까지 null이다 — 1번이 2번보다 앞")
		void putsSessionNotReadyBeforeTheBeforeOpenCheck() {
			givenNotReadySession();
			at(LocalTime.of(8, 0));

			MarketBriefingResponse response = queryService.getBriefing(Market.STOCK);

			assertThat(response.status()).isEqualTo(FeedbackContentStatus.EMPTY);
			assertThat(response.originTradeDate()).isNull();
			assertThat(response.items()).isEmpty();
			verifyNoInteractions(marketNewsItemRepository);
			verify(marketBriefingRepository, never()).findByMarketAndOriginTradeDate(any(), any());
		}

		// 상태값 ① — Part C는 같은 상황에서 NOT_YET이다. 의도된 차이라 값이 붙어 다니는지 고정한다.
		@Test
		@DisplayName("세션 미준비면 장중 시각이어도 EMPTY이고 NOT_YET이 아니다")
		void returnsEmptyNotNotYetWhenTheSessionIsNotReadyDuringTradingHours() {
			givenNotReadySession();
			at(LocalTime.of(11, 0));

			assertThat(queryService.getBriefing(Market.STOCK).status())
				.isEqualTo(FeedbackContentStatus.EMPTY);
		}

		@Test
		@DisplayName("세션은 READY이고 09:00 이전이면 NOT_YET이고 originTradeDate는 채워진다 — 2번")
		void returnsNotYetWithTheTradeDateBeforeMarketOpen() {
			givenReadySession();
			at(LocalTime.of(8, 59, 59));

			MarketBriefingResponse response = queryService.getBriefing(Market.STOCK);

			assertThat(response.status()).isEqualTo(FeedbackContentStatus.NOT_YET);
			assertThat(response.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
			assertThat(response.items()).isEmpty();
		}

		// 09:00 하한이 Part C와 같아야 게이트 ⑦이 성립한다. 부등호가 <= 로 바뀌면 개장 정각이 닫힌다.
		@Test
		@DisplayName("09:00 정각에는 더 이상 NOT_YET이 아니다")
		void opensExactlyAtMarketOpen() {
			givenReadySession();
			givenMarketNews(List.of());
			givenMarketDisclosures(List.of());
			at(LocalTime.of(9, 0));

			assertThat(queryService.getBriefing(Market.STOCK).status())
				.isNotEqualTo(FeedbackContentStatus.NOT_YET);
		}

		// 3번이 4·5번보다 앞이다. 기사 0건 + summary가 null인 행이 두 조건이 겹치는 자리다 — 순서가
		// 뒤바뀌면 "기사도 없고 서술도 없는" 날이 UNAVAILABLE로 보인다.
		@Test
		@DisplayName("기사 0건이면 summary가 null인 행이 있어도 EMPTY다 — 3번이 5번보다 앞")
		void putsTheEmptyItemsCheckBeforeTheBriefingRowLookup() {
			givenReadySession();
			givenMarketNews(List.of());
			givenMarketDisclosures(List.of());
			givenBriefingRow(Optional.of(briefingRow(null)));
			at(LocalTime.of(10, 0));

			MarketBriefingResponse response = queryService.getBriefing(Market.STOCK);

			assertThat(response.status()).isEqualTo(FeedbackContentStatus.EMPTY);
			assertThat(response.items()).isEmpty();
			assertThat(response.summary()).isNull();
		}

		@Test
		@DisplayName("기사 0건이면 서술이 있는 행이 있어도 READY가 아니라 EMPTY다 — 3번이 6번보다 앞")
		void neverReturnsReadyWhenThereIsNoArticleEvenWithANarrative() {
			givenReadySession();
			givenMarketNews(List.of());
			givenMarketDisclosures(List.of());
			givenBriefingRow(Optional.of(briefingRow("간밤 기사가 이어졌습니다.")));
			at(LocalTime.of(10, 0));

			MarketBriefingResponse response = queryService.getBriefing(Market.STOCK);

			assertThat(response.status()).isEqualTo(FeedbackContentStatus.EMPTY);
			assertThat(response.summary()).isNull();
		}

		// 4번과 5번은 items가 같고 상태값으로만 갈린다. 저장된 행은 둘 다 summary가 NULL이라
		// existsBy로는 구분되지 않는다 — findBy(Optional)를 쓰는 이유가 이 한 쌍이다.
		@Test
		@DisplayName("브리핑 행이 없고 기사가 있으면 EMPTY이고 items는 채운다 — 4번")
		void returnsEmptyWithFilledItemsWhenTheBriefingRowIsMissing() {
			givenReadySession();
			givenMarketNews(List.of(news(1L, "테스트종목A", LocalTime.of(18, 0))));
			givenMarketDisclosures(List.of());
			givenBriefingRow(Optional.empty());
			at(LocalTime.of(10, 0));

			MarketBriefingResponse response = queryService.getBriefing(Market.STOCK);

			assertThat(response.status()).isEqualTo(FeedbackContentStatus.EMPTY);
			assertThat(response.items()).hasSize(1);
			assertThat(response.summary()).isNull();
		}

		@Test
		@DisplayName("행이 있고 summary가 null이면 UNAVAILABLE이고 items는 채운다 — 5번")
		void returnsUnavailableWithFilledItemsWhenTheRowHasNoNarrative() {
			givenReadySession();
			givenMarketNews(List.of(news(1L, "테스트종목A", LocalTime.of(18, 0))));
			givenMarketDisclosures(List.of());
			givenBriefingRow(Optional.of(briefingRow(null)));
			at(LocalTime.of(10, 0));

			MarketBriefingResponse response = queryService.getBriefing(Market.STOCK);

			assertThat(response.status()).isEqualTo(FeedbackContentStatus.UNAVAILABLE);
			assertThat(response.items()).hasSize(1);
			assertThat(response.summary()).isNull();
		}

		@Test
		@DisplayName("행이 있고 서술이 있으면 READY이고 문장이 실린다 — 6번")
		void returnsReadyWithTheNarrative() {
			givenReadySession();
			givenMarketNews(List.of(news(1L, "테스트종목A", LocalTime.of(18, 0))));
			givenMarketDisclosures(List.of());
			givenBriefingRow(Optional.of(briefingRow("간밤 기사가 이어졌습니다.")));
			at(LocalTime.of(10, 0));

			MarketBriefingResponse response = queryService.getBriefing(Market.STOCK);

			assertThat(response.status()).isEqualTo(FeedbackContentStatus.READY);
			assertThat(response.summary()).isEqualTo("간밤 기사가 이어졌습니다.");
			assertThat(response.market()).isEqualTo(Market.STOCK);
			assertThat(response.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
		}

		// --- 응답 상한 (§C-7) ---

		// 이 클래스 안에 상한이 둘 있다 — 생성은 max-items-per-summary(5), 조회는 max-items-per-briefing(3).
		// 두 값을 다르게 준 픽스처라야 바꿔 쓴 구현이 드러난다. 같은 값이면 뒤바꿔도 전부 초록이다.
		@Test
		@DisplayName("조회 items는 max-items-per-briefing으로 자른다 — 생성의 LLM 입력 상한이 아니다")
		void truncatesResponseItemsWithTheBriefingLimitNotTheSummaryLimit() {
			givenReadySession();
			List<MarketNewsItem> newsItems = new ArrayList<>();
			for (int index = 0; index < 10; index++) {
				newsItems.add(news(index + 1L, "테스트종목" + index, LocalTime.of(16, 0).plusMinutes(index * 10L)));
			}
			givenMarketNews(newsItems);
			givenMarketDisclosures(List.of());
			givenBriefingRow(Optional.of(briefingRow("간밤 기사가 이어졌습니다.")));
			at(LocalTime.of(10, 0));

			assertThat(queryService.getBriefing(Market.STOCK).items())
				.as("생성 상한(%d)으로 잘리면 두 자리를 바꿔 쓴 것이다", MAX_ITEMS_PER_SUMMARY)
				.hasSize(MAX_ITEMS_PER_BRIEFING);
		}

		// 브리핑은 전 종목 합산 단일 목록이라 공시가 상시 전멸하는 자리다 (§뉴스 매칭 범위, 항목 3 판정).
		@Test
		@DisplayName("상한을 넘어도 공시가 items에 남는다")
		void keepsDisclosuresInTheResponseItemsWhenOverTheLimit() {
			givenReadySession();
			List<MarketNewsItem> newsItems = new ArrayList<>();
			for (int index = 0; index < 10; index++) {
				newsItems.add(news(index + 1L, "테스트종목" + index, LocalTime.of(16, 0).plusMinutes(index * 10L)));
			}
			givenMarketNews(newsItems);
			givenMarketDisclosures(List.of(disclosure(101L, "공시종목A"), disclosure(102L, "공시종목B")));
			givenBriefingRow(Optional.of(briefingRow("간밤 기사가 이어졌습니다.")));
			at(LocalTime.of(10, 0));

			List<BriefingNewsItem> items = queryService.getBriefing(Market.STOCK).items();

			assertThat(items).hasSize(MAX_ITEMS_PER_BRIEFING);
			assertThat(items).filteredOn(item -> item.type() == MarketNewsItemType.DISCLOSURE).hasSize(2);
			assertThat(items).extracting(BriefingNewsItem::publishedAt)
				.isSortedAccordingTo(java.util.Comparator.reverseOrder());
		}

		// 화면이 추가 조회를 하지 않아도 되도록 종목 3값을 평평하게 담는다 (§C-6).
		@Test
		@DisplayName("items 항목이 종목 id·심볼·이름을 함께 담는다")
		void putsTheInstrumentIdentityIntoEveryItem() {
			givenReadySession();
			givenMarketNews(List.of(news(1L, "테스트종목A", LocalTime.of(18, 0))));
			givenMarketDisclosures(List.of());
			givenBriefingRow(Optional.of(briefingRow("간밤 기사가 이어졌습니다.")));
			at(LocalTime.of(10, 0));

			assertThat(queryService.getBriefing(Market.STOCK).items())
				.singleElement()
				.satisfies(item -> {
					assertThat(item.instrumentId()).isNotNull();
					assertThat(item.symbol()).isNotBlank();
					assertThat(item.name()).isEqualTo("테스트종목A");
				});
		}

		// --- 구간 (게이트 ⑪) ---

		// 브리핑은 조회 시각이 언제든 전장만 담는다 — Part C처럼 상한이 재생 시각을 따라 넓어지면
		// 장중 기사가 아침 브리핑에 들어간다 (FEED-009).
		@Test
		@DisplayName("장중에 조회해도 구간 상한이 09:00에서 넓어지지 않는다")
		void neverWidensTheWindowBeyondMarketOpenDuringTradingHours() {
			givenReadySession();
			givenMarketNews(List.of());
			givenMarketDisclosures(List.of());
			at(LocalTime.of(14, 0));

			queryService.getBriefing(Market.STOCK);

			verify(marketNewsItemRepository, atLeastOnce())
				.findMarketNewsPublishedBetween(Market.STOCK, PRE_MARKET_FROM, PRE_MARKET_TO);
		}

		@Test
		@DisplayName("장 마감 이후에 조회해도 D 접수 공시를 묻지 않는다")
		void neverAsksForOriginDayDisclosuresAfterTheClose() {
			givenReadySession();
			givenMarketNews(List.of());
			givenMarketDisclosures(List.of());
			at(LocalTime.of(18, 0));

			queryService.getBriefing(Market.STOCK);

			verify(marketNewsItemRepository, never()).findMarketDisclosuresReceivedOn(
				any(), eq(ORIGIN_TRADE_DATE.atStartOfDay()), any());
		}

		// --- 그 밖 ---

		// 코인은 재생세션·개장 시각과 무관하다 — 주식 규칙을 태우면 24시간 거래 시장이 매일 09:00까지
		// NOT_YET이 된다. 03:00은 주식이라면 개장 전이라 그 회귀가 드러나는 시각이다.
		@Test
		@DisplayName("코인 시장은 주식 게이트를 타지 않고 기사가 0건이면 EMPTY다")
		void returnsEmptyForCryptoWithoutApplyingTheStockGate() {
			at(LocalTime.of(3, 0));
			givenMarketNews(List.of());

			MarketBriefingResponse response = queryService.getBriefing(Market.CRYPTO);

			assertThat(response.status()).isEqualTo(FeedbackContentStatus.EMPTY);
			assertThat(response.market()).isEqualTo(Market.CRYPTO);
			assertThat(response.originTradeDate()).isNull();
			assertThat(response.items()).isEmpty();
			verifyNoInteractions(stockReplayService);
		}

		// 위 단정만 있으면 "기사도 행도 없는" 상태에서만 통과한다 — 실제로 만들어진 브리핑이 있는 상태의
		// 자리가 비어 있었다. 배치 ⑪(generated_at 최신 1행)이 조회에서 성립하는지가 여기서 갈린다.
		@Test
		@DisplayName("코인 조회는 최근 24시간 창으로 묻고 generated_at 최신 1행의 문장을 준다")
		void returnsTheLatestGeneratedCryptoBriefingWithinTheRollingWindow() {
			at(LocalTime.of(3, 0));
			givenMarketNews(List.of(news(1L, "비트코인", LocalTime.of(2, 0))));
			when(marketBriefingRepository.findFirstByMarketOrderByGeneratedAtDescIdDesc(Market.CRYPTO))
				.thenReturn(Optional.of(MarketBriefing.create(
					Market.CRYPTO, LocalDate.of(2026, 8, 5), "최근 24시간 기사가 이어졌습니다.",
					NarrativeSource.LLM, LocalDateTime.of(2026, 8, 5, 23, 5))));

			MarketBriefingResponse response = queryService.getBriefing(Market.CRYPTO);

			assertThat(response.status()).isEqualTo(FeedbackContentStatus.READY);
			assertThat(response.summary()).isEqualTo("최근 24시간 기사가 이어졌습니다.");
			assertThat(response.items()).hasSize(1);
			// 저장된 행의 origin_trade_date는 배치 실행 날짜라 응답에 내리지 않는다 (§C-9).
			assertThat(response.originTradeDate()).isNull();
			LocalDateTime now = LocalDateTime.of(SERVICE_DATE, LocalTime.of(3, 0));
			verify(marketNewsItemRepository)
				.findMarketNewsPublishedBetween(Market.CRYPTO, now.minusHours(24), now);
			// 주식 경로의 "오늘 날짜 행" 조회로 되돌아가면 자정 직후와 배치 실패 시각마다 화면이 빈다.
			verify(marketBriefingRepository, never()).findByMarketAndOriginTradeDate(eq(Market.CRYPTO), any());
		}

		// 배치 ⑪ — 오늘 행이 없어도 직전에 만들어 둔 브리핑은 여전히 유효하다. "오늘 날짜 행"으로 찾으면
		// 매일 00:00~00:05와 배치 실패 시각마다 화면이 빈다.
		@Test
		@DisplayName("자정 직후에 오늘 행이 없어도 어제 만든 브리핑이 그대로 나온다")
		void keepsServingYesterdaysBriefingRightAfterMidnight() {
			at(LocalTime.of(0, 3));
			givenMarketNews(List.of(news(1L, "비트코인", LocalTime.of(0, 1))));
			when(marketBriefingRepository.findFirstByMarketOrderByGeneratedAtDescIdDesc(Market.CRYPTO))
				.thenReturn(Optional.of(MarketBriefing.create(
					Market.CRYPTO, SERVICE_DATE.minusDays(1), "어제 23시 05분 기준 요약입니다.",
					NarrativeSource.LLM, LocalDateTime.of(SERVICE_DATE.minusDays(1), LocalTime.of(23, 5)))));

			MarketBriefingResponse response = queryService.getBriefing(Market.CRYPTO);

			assertThat(response.status()).isEqualTo(FeedbackContentStatus.READY);
			assertThat(response.summary()).isEqualTo("어제 23시 05분 기준 요약입니다.");
		}

		@Test
		@DisplayName("코인 행이 없고 기사가 있으면 EMPTY이고 items는 채운다")
		void returnsEmptyWithFilledItemsWhenTheCryptoRowIsMissing() {
			at(LocalTime.of(3, 0));
			givenMarketNews(List.of(news(1L, "비트코인", LocalTime.of(2, 0))));
			when(marketBriefingRepository.findFirstByMarketOrderByGeneratedAtDescIdDesc(Market.CRYPTO))
				.thenReturn(Optional.empty());

			MarketBriefingResponse response = queryService.getBriefing(Market.CRYPTO);

			assertThat(response.status()).isEqualTo(FeedbackContentStatus.EMPTY);
			assertThat(response.items()).hasSize(1);
		}

		// 조회는 쓰지 않는다 (FEED-009 — GET은 LLM을 호출하지도 DB에 쓰지도 않는다).
		@Test
		@DisplayName("조회 경로가 브리핑을 저장하지도 LLM을 부르지도 않는다")
		void neverWritesOrCallsTheLlmWhileQuerying() {
			givenReadySession();
			givenMarketNews(List.of(news(1L, "테스트종목A", LocalTime.of(18, 0))));
			givenMarketDisclosures(List.of());
			givenBriefingRow(Optional.empty());
			at(LocalTime.of(10, 0));

			queryService.getBriefing(Market.STOCK);

			verify(marketBriefingRepository, never()).save(any());
			verifyNoInteractions(narrativeService);
		}
	}

	// 코인 브리핑 갱신은 주식과 규칙이 셋 다르다 — 한 범위(최근 24시간)·UPSERT·created_at 기준 재생성
	// 판정이다(§C-2·§C-9·FEED-009). UPSERT가 실제로 하루 1행을 유지하는지는 실 DB가 필요해
	// CryptoFeedbackBatchIntegrationTest가 맡고, 여기서는 판정과 저장 인자를 본다.
	@Nested
	@DisplayName("코인 갱신 (배치 ⑩·⑫·§C-9)")
	class CryptoRefresh {

		private final MutableClock clock = new MutableClock(
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 5)).atZone(KST).toInstant());

		private final MarketBriefingService cryptoService = new MarketBriefingService(
			marketNewsItemRepository,
			marketBriefingRepository,
			stockReplayService,
			narrativeService,
			briefingReader(),
			loaderDirectCache(),
			properties,
			clock);

		private final LocalDateTime batchAt = LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 5));

		private MarketBriefing previousRow(LocalDateTime generatedAt) {
			return MarketBriefing.create(
				Market.CRYPTO, generatedAt.toLocalDate(), "직전 요약", NarrativeSource.LLM, generatedAt);
		}

		private void givenNoPreviousRow() {
			when(marketBriefingRepository.findFirstByMarketOrderByGeneratedAtDescIdDesc(Market.CRYPTO))
				.thenReturn(Optional.empty());
			when(marketBriefingRepository.findByMarketAndOriginTradeDate(any(), any()))
				.thenReturn(Optional.empty());
			when(narrativeService.resolveMarketBriefingNarrative(any()))
				.thenReturn(NarrativeResultDto.llm("최근 24시간 코인 기사가 이어졌습니다."));
			when(marketBriefingRepository.save(any())).thenAnswer(call -> call.getArgument(0));
		}

		private MarketBriefing capturedSaved() {
			ArgumentCaptor<MarketBriefing> captor = ArgumentCaptor.forClass(MarketBriefing.class);
			verify(marketBriefingRepository).save(captor.capture());
			return captor.getValue();
		}

		// 배치 ⑩ — 직전 생성 이후 수집된 코인 기사가 없으면 LLM을 부르지 않는다. 기준은 created_at이다.
		@Test
		@DisplayName("직전 생성 이후 수집된 기사가 없으면 LLM을 부르지 않고 저장도 하지 않는다")
		void skipsWithoutCallingTheLlmWhenNothingWasCollectedSinceTheLastGeneration() {
			LocalDateTime lastGeneratedAt = batchAt.minusHours(1);
			when(marketBriefingRepository.findFirstByMarketOrderByGeneratedAtDescIdDesc(Market.CRYPTO))
				.thenReturn(Optional.of(previousRow(lastGeneratedAt)));
			when(marketNewsItemRepository.existsCollectedAfter(Market.CRYPTO, lastGeneratedAt))
				.thenReturn(false);

			assertThat(cryptoService.refreshCryptoBriefing()).isEmpty();

			verifyNoInteractions(narrativeService);
			verify(marketBriefingRepository, never()).save(any());
		}

		@Test
		@DisplayName("직전 생성 행이 없으면 재생성 판정 없이 만든다")
		void generatesWithoutTheRegenerationCheckOnTheFirstRun() {
			givenNoPreviousRow();
			givenMarketNews(List.of(news(1L, "비트코인", LocalTime.of(9, 0))));

			assertThat(cryptoService.refreshCryptoBriefing()).isPresent();

			verify(marketNewsItemRepository, never()).existsCollectedAfter(any(), any());
		}

		// 코인은 '전장'도 '거래일 경계'도 없다 — 주식 구간을 쓰면 재생 시간축이 없는 시장에 원본 거래일
		// 구간이 붙어 매일 0건이 된다.
		@Test
		@DisplayName("최근 24시간 창의 코인 뉴스만 모으고 공시·주식 구간을 쓰지 않는다")
		void collectsOnlyCryptoNewsFromTheRollingWindow() {
			givenNoPreviousRow();
			givenMarketNews(List.of(news(1L, "비트코인", LocalTime.of(9, 0))));

			cryptoService.refreshCryptoBriefing();

			verify(marketNewsItemRepository)
				.findMarketNewsPublishedBetween(Market.CRYPTO, batchAt.minusHours(24), batchAt);
			// 코인은 공시가 없다 (§C-3).
			verify(marketNewsItemRepository, never()).findMarketDisclosuresReceivedOn(any(), any(), any());
		}

		// §C-9 — origin_trade_date는 배치 실행 시점의 KST 날짜다. 비우면 유니크가 중복을 허용해
		// UPSERT가 매시 새 행을 쌓는다.
		@Test
		@DisplayName("origin_trade_date가 배치 실행 시점의 KST 날짜이고 시장이 CRYPTO다")
		void storesTheBatchRunDateAndCryptoMarket() {
			givenNoPreviousRow();
			givenMarketNews(List.of(news(1L, "비트코인", LocalTime.of(9, 0))));

			cryptoService.refreshCryptoBriefing();

			MarketBriefing saved = capturedSaved();
			assertThat(saved.getMarket()).isEqualTo(Market.CRYPTO);
			assertThat(saved.getOriginTradeDate()).isEqualTo(SERVICE_DATE);
			assertThat(saved.getGeneratedAt()).isEqualTo(batchAt);
		}

		// §C-9 — 같은 날 두 번째 실행은 새 행이 아니라 같은 행의 갱신이다. 새 행을 만들면 유니크에 걸려
		// 그 시각 갱신이 통째로 실패한다.
		@Test
		@DisplayName("같은 날 행이 이미 있으면 새 행을 만들지 않고 그 행을 갱신한다")
		void updatesTheExistingRowOfTheSameDayInsteadOfInsertingANewOne() {
			LocalDateTime lastGeneratedAt = batchAt.minusHours(1);
			MarketBriefing existing = previousRow(lastGeneratedAt);
			when(marketBriefingRepository.findFirstByMarketOrderByGeneratedAtDescIdDesc(Market.CRYPTO))
				.thenReturn(Optional.of(existing));
			when(marketNewsItemRepository.existsCollectedAfter(any(), any())).thenReturn(true);
			when(marketBriefingRepository.findByMarketAndOriginTradeDate(Market.CRYPTO, SERVICE_DATE))
				.thenReturn(Optional.of(existing));
			when(narrativeService.resolveMarketBriefingNarrative(any()))
				.thenReturn(NarrativeResultDto.llm("새 브리핑입니다."));
			when(marketBriefingRepository.save(any())).thenAnswer(call -> call.getArgument(0));
			givenMarketNews(List.of(news(1L, "비트코인", LocalTime.of(9, 0))));

			cryptoService.refreshCryptoBriefing();

			MarketBriefing saved = capturedSaved();
			assertThat(saved).isSameAs(existing);
			assertThat(saved.getSummary()).isEqualTo("새 브리핑입니다.");
			assertThat(saved.getGeneratedAt()).isEqualTo(batchAt);
			// 유니크 축은 건드리지 않는다.
			assertThat(saved.getMarket()).isEqualTo(Market.CRYPTO);
			assertThat(saved.getOriginTradeDate()).isEqualTo(lastGeneratedAt.toLocalDate());
		}

		// 주식 경로가 refreshNarrative를 쓰면 같은 서비스 날짜의 두 번째 실행이 기존 행을 갈아 끼워
		// 배치 ⑤가 "덮어쓴다"로 조용히 바뀐다.
		@Test
		@DisplayName("주식 생성 경로는 기존 행이 있어도 갱신하지 않고 건너뛴다")
		void neverRefreshesAnExistingRowOnTheStockPath() {
			when(marketBriefingRepository.existsByMarketAndOriginTradeDate(Market.STOCK, ORIGIN_TRADE_DATE))
				.thenReturn(true);

			cryptoService.generateStockBriefing(ORIGIN_TRADE_DATE);

			verify(marketBriefingRepository, never()).save(any());
			verify(marketBriefingRepository, never()).findFirstByMarketOrderByGeneratedAtDescIdDesc(any());
		}
	}

	// 같은 픽스처를 여러 시각에서 조회해야 §C-4의 1·2번 경계를 볼 수 있다.
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
