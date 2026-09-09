package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.domain.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.domain.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.MarketBriefing;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.repository.MarketBriefingRepository;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.store.FeedbackQueryCache;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.BusinessDayCalendar;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.market.service.StockReplaySessionDto;
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

class MarketBriefingServiceTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 6, 8, 45);

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDateTime PRE_MARKET_FROM = LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 30));
	private static final LocalDateTime PRE_MARKET_TO = LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 0));

	private static final int MAX_ITEMS_PER_SUMMARY = 5;

	private static final int MAX_ITEMS_PER_BRIEFING = 3;

	private final MarketNewsItemRepository marketNewsItemRepository = mock(MarketNewsItemRepository.class);

	private final MarketBriefingRepository marketBriefingRepository = mock(MarketBriefingRepository.class);

	private final NarrativeService narrativeService = mock(NarrativeService.class);

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

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

	private MarketBriefingReader briefingReader() {
		return new MarketBriefingReader(
			marketNewsItemRepository, marketBriefingRepository, new BusinessDayCalendar(), properties);
	}

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

	@Test
	@DisplayName("전장 구간 [D-1 15:30, D 09:00]으로 시장 전체 뉴스를 조회한다")
	void queriesMarketWideNewsWithinThePreMarketWindow() {
		givenNoDuplicateAndNarrative();
		givenMarketNews(List.of(news(1L, "테스트종목A", LocalTime.of(18, 0))));

		service.generateStockBriefing(ORIGIN_TRADE_DATE);

		verify(marketNewsItemRepository)
			.findMarketNewsPublishedBetween(Market.STOCK, PRE_MARKET_FROM, PRE_MARKET_TO);
	}

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

	@Test
	@DisplayName("상한을 넘어도 공시가 프롬프트에 남고 전체 건수는 상한을 넘지 않는다")
	void keepsDisclosuresInThePromptEvenWhenTheMarketWideListIsOverTheLimit() {
		givenNoDuplicateAndNarrative();
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
			assertThat(response.originTradeDate()).isNull();
			LocalDateTime now = LocalDateTime.of(SERVICE_DATE, LocalTime.of(3, 0));
			verify(marketNewsItemRepository)
				.findMarketNewsPublishedBetween(Market.CRYPTO, now.minusHours(24), now);
			verify(marketBriefingRepository, never()).findByMarketAndOriginTradeDate(eq(Market.CRYPTO), any());
		}

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

		@Test
		@DisplayName("최근 24시간 창의 코인 뉴스만 모으고 공시·주식 구간을 쓰지 않는다")
		void collectsOnlyCryptoNewsFromTheRollingWindow() {
			givenNoPreviousRow();
			givenMarketNews(List.of(news(1L, "비트코인", LocalTime.of(9, 0))));

			cryptoService.refreshCryptoBriefing();

			verify(marketNewsItemRepository)
				.findMarketNewsPublishedBetween(Market.CRYPTO, batchAt.minusHours(24), batchAt);
			verify(marketNewsItemRepository, never()).findMarketDisclosuresReceivedOn(any(), any(), any());
		}

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
			assertThat(saved.getMarket()).isEqualTo(Market.CRYPTO);
			assertThat(saved.getOriginTradeDate()).isEqualTo(lastGeneratedAt.toLocalDate());
		}

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
