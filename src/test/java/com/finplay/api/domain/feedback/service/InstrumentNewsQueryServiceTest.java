package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.domain.feedback.dto.response.InstrumentNewsResponse;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
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
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.market.service.StockReplaySessionDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class InstrumentNewsQueryServiceTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);
	private static final LocalDate PREVIOUS_TRADE_DATE = LocalDate.of(2026, 8, 4);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final long STOCK_ID = 7L;
	private static final long CRYPTO_ID = 8L;

	private final InstrumentService instrumentService = mock(InstrumentService.class);

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final MarketNewsItemRepository marketNewsItemRepository = mock(MarketNewsItemRepository.class);

	private final InstrumentNewsSummaryRepository instrumentNewsSummaryRepository = mock(
		InstrumentNewsSummaryRepository.class);

	private final MutableClock clock = new MutableClock(
		LocalDateTime.of(SERVICE_DATE, LocalTime.of(10, 0)).atZone(KST).toInstant());

	private final InstrumentNewsQueryService service = new InstrumentNewsQueryService(
		new InstrumentNewsQueryReader(
			instrumentService,
			marketNewsItemRepository,
			instrumentNewsSummaryRepository,
			new BusinessDayCalendar(),
			new FeedbackNewsProperties("0 0/30 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, 5, 50, 30, 30)),
		stockReplayService,
		loaderDirectCache(),
		clock);

	private static FeedbackQueryCache loaderDirectCache() {
		return new FeedbackQueryCache(
			null, null, null, Clock.system(KST), new FeedbackQueryCacheProperties(false, 1000, 300, 20), null);
	}

	private static Instrument instrument(Market market, long id, String symbol) {
		Instrument created = Instrument.create(
			market, symbol, "테스트종목", BigDecimal.ONE, 10000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(created, "id", id);
		return created;
	}

	private static MarketNewsItem news(long id, LocalDateTime publishedAt) {
		MarketNewsItem item = MarketNewsItem.create(
			instrument(Market.STOCK, STOCK_ID, "QRY001"),
			MarketNewsItemType.NEWS,
			"기사-" + id,
			"테스트경제",
			"https://news.example.test/" + id,
			publishedAt,
			publishedAt);
		ReflectionTestUtils.setField(item, "id", id);
		return item;
	}

	private static InstrumentNewsSummary summaryRow(NewsSummaryScope scope, String text) {
		return InstrumentNewsSummary.create(
			instrument(Market.STOCK, STOCK_ID, "QRY001"),
			ORIGIN_TRADE_DATE,
			scope,
			text,
			text == null ? NarrativeSource.NONE : NarrativeSource.LLM,
			LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 45)));
	}

	private void givenStockWithReadySession() {
		when(instrumentService.getInstrumentEntity(STOCK_ID))
			.thenReturn(instrument(Market.STOCK, STOCK_ID, "QRY001"));
		when(stockReplayService.getCurrentReplaySession())
			.thenReturn(new StockReplaySessionDto(true, ORIGIN_TRADE_DATE));
	}

	private void givenNews(List<MarketNewsItem> items) {
		when(marketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
			anyLong(), any(), any(), any())).thenReturn(items);
	}

	private void givenSummary(Optional<InstrumentNewsSummary> row) {
		when(instrumentNewsSummaryRepository.findByInstrumentIdAndOriginTradeDateAndScope(
			anyLong(), any(), any())).thenReturn(row);
	}

	private void at(LocalTime time) {
		clock.set(LocalDateTime.of(SERVICE_DATE, time));
	}

	@Nested
	@DisplayName("§C-4 판정 순서 — 두 조건이 동시에 성립하는 구간에서만 순서가 드러난다")
	class DecisionOrder {

		@Test
		@DisplayName("세션 미준비 + 개장 전이면 originTradeDate까지 null이다 — 1번이 2번보다 앞")
		void putsSessionNotReadyBeforeTheBeforeOpenCheck() {
			when(instrumentService.getInstrumentEntity(STOCK_ID))
				.thenReturn(instrument(Market.STOCK, STOCK_ID, "QRY001"));
			when(stockReplayService.getCurrentReplaySession())
				.thenReturn(new StockReplaySessionDto(false, null));
			at(LocalTime.of(8, 0));

			InstrumentNewsResponse response = service.getInstrumentNews(STOCK_ID);

			assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.NOT_YET);
			assertThat(response.originTradeDate()).isNull();
			assertThat(response.items()).isEmpty();
			verifyNoInteractions(marketNewsItemRepository);
			verifyNoInteractions(instrumentNewsSummaryRepository);
		}

		@Test
		@DisplayName("세션 미준비면 장중 시각이어도 NOT_YET이다")
		void returnsNotYetWhenTheSessionIsNotReadyEvenDuringTradingHours() {
			when(instrumentService.getInstrumentEntity(STOCK_ID))
				.thenReturn(instrument(Market.STOCK, STOCK_ID, "QRY001"));
			when(stockReplayService.getCurrentReplaySession())
				.thenReturn(new StockReplaySessionDto(false, null));
			at(LocalTime.of(11, 0));

			assertThat(service.getInstrumentNews(STOCK_ID).summaryStatus())
				.isEqualTo(FeedbackContentStatus.NOT_YET);
		}

		@Test
		@DisplayName("세션은 READY이고 09:00 이전이면 NOT_YET이고 originTradeDate는 채워진다 — 2번")
		void returnsNotYetWithTheTradeDateBeforeMarketOpen() {
			givenStockWithReadySession();
			at(LocalTime.of(8, 59, 59));

			InstrumentNewsResponse response = service.getInstrumentNews(STOCK_ID);

			assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.NOT_YET);
			assertThat(response.originTradeDate()).isEqualTo(ORIGIN_TRADE_DATE);
			assertThat(response.summaryScope()).isNull();
		}

		@Test
		@DisplayName("09:00 정각에는 더 이상 NOT_YET이 아니다")
		void opensExactlyAtMarketOpen() {
			givenStockWithReadySession();
			givenNews(List.of());
			at(LocalTime.of(9, 0));

			assertThat(service.getInstrumentNews(STOCK_ID).summaryStatus())
				.isNotEqualTo(FeedbackContentStatus.NOT_YET);
		}

		@Test
		@DisplayName("기사 0건이면 summary가 null인 행이 있어도 EMPTY다 — 3번이 5번보다 앞")
		void putsTheEmptyItemsCheckBeforeTheSummaryRowLookup() {
			givenStockWithReadySession();
			givenNews(List.of());
			givenSummary(Optional.of(summaryRow(NewsSummaryScope.PRE_MARKET, null)));
			at(LocalTime.of(10, 0));

			InstrumentNewsResponse response = service.getInstrumentNews(STOCK_ID);

			assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.EMPTY);
			assertThat(response.items()).isEmpty();
			assertThat(response.summary()).isNull();
		}

		@Test
		@DisplayName("기사 0건이면 서술이 있는 행이 있어도 READY가 아니라 EMPTY다 — 3번이 6번보다 앞")
		void neverReturnsReadyWhenThereIsNoArticleEvenWithANarrative() {
			givenStockWithReadySession();
			givenNews(List.of());
			givenSummary(Optional.of(summaryRow(NewsSummaryScope.PRE_MARKET, "전일 저녁 기사가 있었습니다.")));
			at(LocalTime.of(10, 0));

			InstrumentNewsResponse response = service.getInstrumentNews(STOCK_ID);

			assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.EMPTY);
			assertThat(response.summary()).isNull();
		}

		@Test
		@DisplayName("요약 행이 없고 기사가 있으면 EMPTY이고 items는 채운다 — 4번")
		void returnsEmptyWithFilledItemsWhenTheSummaryRowIsMissing() {
			givenStockWithReadySession();
			givenNews(List.of(news(1L, LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)))));
			givenSummary(Optional.empty());
			at(LocalTime.of(10, 0));

			InstrumentNewsResponse response = service.getInstrumentNews(STOCK_ID);

			assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.EMPTY);
			assertThat(response.items()).hasSize(1);
			assertThat(response.summary()).isNull();
		}

		@Test
		@DisplayName("행이 있고 summary가 null이면 UNAVAILABLE이고 items는 채운다 — 5번")
		void returnsUnavailableWithFilledItemsWhenTheRowHasNoNarrative() {
			givenStockWithReadySession();
			givenNews(List.of(news(1L, LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)))));
			givenSummary(Optional.of(summaryRow(NewsSummaryScope.PRE_MARKET, null)));
			at(LocalTime.of(10, 0));

			InstrumentNewsResponse response = service.getInstrumentNews(STOCK_ID);

			assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.UNAVAILABLE);
			assertThat(response.items()).hasSize(1);
			assertThat(response.summary()).isNull();
		}

		@Test
		@DisplayName("행이 있고 서술이 있으면 READY이고 문장이 실린다 — 6번")
		void returnsReadyWithTheNarrative() {
			givenStockWithReadySession();
			givenNews(List.of(news(1L, LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)))));
			givenSummary(Optional.of(summaryRow(NewsSummaryScope.PRE_MARKET, "전일 저녁 기사가 있었습니다.")));
			at(LocalTime.of(10, 0));

			InstrumentNewsResponse response = service.getInstrumentNews(STOCK_ID);

			assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.READY);
			assertThat(response.summary()).isEqualTo("전일 저녁 기사가 있었습니다.");
			assertThat(response.summaryScope()).isEqualTo(NewsSummaryScope.PRE_MARKET);
		}
	}

	@Nested
	@DisplayName("items 구간과 summaryScope 대응 (§C-2)")
	class VisibleWindow {

		private LocalDateTime capturedNewsUpperBound() {
			org.mockito.ArgumentCaptor<LocalDateTime> captor = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
			verify(marketNewsItemRepository).findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				anyLong(), any(), any(), captor.capture());
			return captor.getValue();
		}

		@Test
		@DisplayName("장중 조회는 뉴스 상한이 현재 재생 시각이고 하한은 D-1 15:30이다")
		void usesTheCurrentReplayTimeAsTheNewsUpperBoundDuringTradingHours() {
			givenStockWithReadySession();
			givenNews(List.of());
			at(LocalTime.of(10, 30));

			service.getInstrumentNews(STOCK_ID);

			verify(marketNewsItemRepository).findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc(
				STOCK_ID,
				MarketNewsItemType.NEWS,
				LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(15, 30)),
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(10, 30)));
		}

		@Test
		@DisplayName("장 마감 이후 조회는 뉴스 상한이 15:30에서 멈춘다")
		void clampsTheNewsUpperBoundAtTheCloseAfterTradingHours() {
			givenStockWithReadySession();
			givenNews(List.of());
			at(LocalTime.of(16, 0));

			service.getInstrumentNews(STOCK_ID);

			assertThat(capturedNewsUpperBound())
				.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 30)));
		}

		@Test
		@DisplayName("장 마감 정각 조회도 15:30에서 멈춘다")
		void clampsTheNewsUpperBoundExactlyAtTheClose() {
			givenStockWithReadySession();
			givenNews(List.of());
			at(LocalTime.of(15, 30));

			service.getInstrumentNews(STOCK_ID);

			assertThat(capturedNewsUpperBound())
				.isEqualTo(LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 30)));
		}

		@Test
		@DisplayName("15:30 전에는 PRE_MARKET 요약을, 15:30부터는 FULL 요약을 본다")
		void mapsTheQueryTimeToTheSummaryScope() {
			givenStockWithReadySession();
			givenNews(List.of(news(1L, LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)))));
			givenSummary(Optional.empty());

			at(LocalTime.of(15, 29, 59));
			assertThat(service.getInstrumentNews(STOCK_ID).summaryScope())
				.isEqualTo(NewsSummaryScope.PRE_MARKET);

			at(LocalTime.of(15, 30));
			assertThat(service.getInstrumentNews(STOCK_ID).summaryScope()).isEqualTo(NewsSummaryScope.FULL);
		}

		@Test
		@DisplayName("조회하는 요약 행의 범위가 그 시각의 summaryScope와 같다")
		void looksUpTheSummaryRowWithTheResolvedScope() {
			givenStockWithReadySession();
			givenNews(List.of(news(1L, LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)))));
			givenSummary(Optional.empty());
			at(LocalTime.of(16, 0));

			service.getInstrumentNews(STOCK_ID);

			verify(instrumentNewsSummaryRepository).findByInstrumentIdAndOriginTradeDateAndScope(
				STOCK_ID, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL);
		}

		@Test
		@DisplayName("PRE_MARKET 구간에서는 D-1 접수 공시만 묻는다")
		void asksOnlyForPreviousDayDisclosuresBeforeTheClose() {
			givenStockWithReadySession();
			givenNews(List.of());
			at(LocalTime.of(10, 0));

			service.getInstrumentNews(STOCK_ID);

			verify(marketNewsItemRepository).findDisclosuresReceivedOn(
				STOCK_ID, PREVIOUS_TRADE_DATE.atStartOfDay(), PREVIOUS_TRADE_DATE.plusDays(1).atStartOfDay());
			verify(marketNewsItemRepository, never()).findDisclosuresReceivedOn(
				anyLong(), eq(ORIGIN_TRADE_DATE.atStartOfDay()), any());
		}

		@Test
		@DisplayName("FULL 구간에서는 D 접수 공시도 함께 묻는다")
		void alsoAsksForOriginDayDisclosuresAfterTheClose() {
			givenStockWithReadySession();
			givenNews(List.of());
			at(LocalTime.of(16, 0));

			service.getInstrumentNews(STOCK_ID);

			verify(marketNewsItemRepository).findDisclosuresReceivedOn(
				STOCK_ID, ORIGIN_TRADE_DATE.atStartOfDay(), ORIGIN_TRADE_DATE.plusDays(1).atStartOfDay());
		}
	}

	@Test
	@DisplayName("코인 종목은 주식 게이트를 타지 않고 ROLLING_24H 범위로 응답한다")
	void returnsRollingScopeForCryptoWithoutApplyingTheStockGate() {
		when(instrumentService.getInstrumentEntity(CRYPTO_ID))
			.thenReturn(instrument(Market.CRYPTO, CRYPTO_ID, "QRYBTC"));
		at(LocalTime.of(3, 0));

		InstrumentNewsResponse response = service.getInstrumentNews(CRYPTO_ID);

		assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.EMPTY);
		assertThat(response.items()).isEmpty();
		assertThat(response.originTradeDate()).isNull();
		assertThat(response.summaryScope()).isEqualTo(NewsSummaryScope.ROLLING_24H);
		verifyNoInteractions(stockReplayService);
	}

	@Test
	@DisplayName("조회 경로가 요약을 저장하지 않는다")
	void neverWritesASummaryRowWhileQuerying() {
		givenStockWithReadySession();
		givenNews(List.of(news(1L, LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)))));
		givenSummary(Optional.empty());
		at(LocalTime.of(10, 0));

		service.getInstrumentNews(STOCK_ID);

		verify(instrumentNewsSummaryRepository, never()).save(any());
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
