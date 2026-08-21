// 종목 뉴스 조회의 §C-4 판정 순서와 items 구간 상한을 검증하는 단위 테스트다.
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
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.feedback.entity.InstrumentNewsSummary;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.dto.response.InstrumentNewsResponse;
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

// 판정 순서의 정본은 spec §C-4의 표다. 순서를 바꾸면 두 조건이 동시에 성립하는 구간에서만 값이 갈리므로,
// 여기서는 그 겹치는 구간을 골라 단정한다 — 한 조건만 성립하는 픽스처로는 순서가 드러나지 않는다.
//
// 게이트가 실제 데이터 위에서 성립하는지와 상한·정렬은 InstrumentNewsQueryGateIntegrationTest가,
// 직렬화·401·404는 InstrumentNewsControllerTest가 맡는다.
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

	// DB 읽기가 Reader로 나갔어도(트랜잭션 경계 분리, PR #257) 이 클래스가 보는 것은 그대로다 — 진짜 Reader에
	// 같은 mock 리포지토리를 그대로 물려 조립하므로 아래 스텁·verify가 한 줄도 바뀌지 않는다.
	private final InstrumentNewsQueryService service = new InstrumentNewsQueryService(
		new InstrumentNewsQueryReader(
			instrumentService,
			marketNewsItemRepository,
			instrumentNewsSummaryRepository,
			new BusinessDayCalendar(),
			// 마지막에서 세 번째가 max-items-per-news-list다 — 이 경로가 쓰는 상한은 그것 하나뿐이다 (§C-7).
			new FeedbackNewsProperties("0 0/30 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, 5, 50, 30, 30)),
		stockReplayService,
		loaderDirectCache(),
		clock);

	// 킬 스위치를 내린(enabled=false) FeedbackQueryCache다 — 항상 로더로 직행하므로 이 클래스의 판정 순서
	// 단정이 캐시 도입 전과 그대로 성립한다. Redis·JSON 협력자는 그 경로에서 한 번도 쓰이지 않아 null로 두고,
	// 키·TTL 재료(Clock)만 실제 값을 준다 — 그 둘은 캐시가 꺼져 있어도 호출 시점에 계산되기 때문이다.
	// 브리핑 items 상한은 이 경로가 쓰지 않아 null이다(캐시가 켜진 동작은 FeedbackQueryCacheTest가 맡는다).
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

		// 1번이 2번보다 앞이다. 08:00은 두 조건이 함께 성립하는 시각이라, 순서가 뒤바뀌면 여기서만 갈린다 —
		// 2번이 먼저면 originTradeDate가 채워지는데 그 날짜는 확정되지도 않은 값이다.
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
			// 아직 열리지 않은 상태에서 기사·요약을 읽을 이유가 없다.
			verifyNoInteractions(marketNewsItemRepository);
			verifyNoInteractions(instrumentNewsSummaryRepository);
		}

		// 상태값 ② — Part C는 세션 미준비에서 NOT_YET이다(Part D는 EMPTY이며 의도된 차이다).
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

		// 09:00 정각에 열린다. 부등호가 <= 로 바뀌면 개장 정각 조회가 1초 동안 NOT_YET으로 남는다.
		@Test
		@DisplayName("09:00 정각에는 더 이상 NOT_YET이 아니다")
		void opensExactlyAtMarketOpen() {
			givenStockWithReadySession();
			givenNews(List.of());
			at(LocalTime.of(9, 0));

			assertThat(service.getInstrumentNews(STOCK_ID).summaryStatus())
				.isNotEqualTo(FeedbackContentStatus.NOT_YET);
		}

		// 3번이 4·5번보다 앞이다. 기사 0건 + 요약 행이 있고 summary가 null인 날이 두 조건이 겹치는 자리다 —
		// 순서가 뒤바뀌면 "기사도 없고 서술도 없는" 날이 UNAVAILABLE로 보인다.
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

		// 같은 순서의 다른 겹침 — 기사가 0건인데 서술은 있는 날이다. 3번이 뒤로 밀리면 READY가 되어
		// 근거 기사 하나 없이 요약 문장만 뜬다.
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

		// 상태값 ③ — 행이 없고 기사가 있다. EMPTY이지만 items는 채운다는 것이 4번의 요지다.
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

		// 상태값 ④ — 행은 있고 서술만 없다. 위와 items가 같아 상태값으로만 구분된다.
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

		// 하한은 요약과 같고 상한만 재생 시각까지 넓다 — 09:00에서 자르면 "재생 시각을 지난 기사만 노출"이 깨진다.
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

		// 상한을 열어 두면 다음 거래일 새벽 기사가 그날 목록에 섞인다 — 16:00에 조회하면 원본 거래일
		// 16:00까지 열리는데, 그 시각대 기사는 이미 다음 거래일 소식이다.
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

		// FULL을 09:00에 노출하면 요약 한 문장이 그날 오후를 통째로 알려준다(FEED-008).
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

		// D 접수 공시가 PRE_MARKET에 섞이면 개장 직후에 그날 장중 접수분이 새어 나간다 (§C-3, 게이트 ⑫).
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

	// 코인 규칙 전반(ROLLING_24H 창·generated_at 최신 1행·재생성 판정)은 코인 경로 이슈가 세운다. 여기서는
	// 주식 규칙에 오염되지 않았는지와 §C-4 3번(기사 0건 → EMPTY·items=[])만 본다 — 새벽 03:00은 주식이라면
	// 개장 전이라 NOT_YET이 되는 시각이다.
	@Test
	@DisplayName("코인 종목은 주식 게이트를 타지 않고 ROLLING_24H 범위로 응답한다")
	void returnsRollingScopeForCryptoWithoutApplyingTheStockGate() {
		when(instrumentService.getInstrumentEntity(CRYPTO_ID))
			.thenReturn(instrument(Market.CRYPTO, CRYPTO_ID, "QRYBTC"));
		at(LocalTime.of(3, 0));

		InstrumentNewsResponse response = service.getInstrumentNews(CRYPTO_ID);

		// 24시간 거래 종목이라 NOT_YET이 존재하지 않는다 (FEED-008).
		assertThat(response.summaryStatus()).isEqualTo(FeedbackContentStatus.EMPTY);
		assertThat(response.items()).isEmpty();
		// 저장된 행의 origin_trade_date는 배치 실행 날짜라 응답에는 내리지 않는다 (§C-9).
		assertThat(response.originTradeDate()).isNull();
		assertThat(response.summaryScope()).isEqualTo(NewsSummaryScope.ROLLING_24H);
		verifyNoInteractions(stockReplayService);
	}

	// 조회는 쓰지 않는다 (FEED-008 — GET은 LLM을 호출하지도 DB에 쓰지도 않는다).
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

	// 같은 픽스처를 여러 시각에서 조회해야 구간 상한과 scope 경계의 양쪽을 볼 수 있다.
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
