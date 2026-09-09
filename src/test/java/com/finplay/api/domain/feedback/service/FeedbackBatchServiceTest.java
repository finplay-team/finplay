package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.StockCandleDto;
import com.finplay.api.domain.market.service.StockReplayService;
import com.finplay.api.domain.market.service.StockReplaySessionDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class FeedbackBatchServiceTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final InstrumentService instrumentService = mock(InstrumentService.class);

	private final PriceMoveDetector priceMoveDetector = mock(PriceMoveDetector.class);

	private final PriceMoveCardService priceMoveCardService = mock(PriceMoveCardService.class);

	private final MarketBriefingService marketBriefingService = mock(MarketBriefingService.class);

	private final InstrumentNewsSummaryService instrumentNewsSummaryService = mock(InstrumentNewsSummaryService.class);

	private final Instrument instrumentA = stock(1L, "005930", "삼성전자");

	private final Instrument instrumentB = stock(2L, "000660", "SK하이닉스");

	private final PriceMoveDetectionDto gap = new PriceMoveDetectionDto(
		PriceMoveEventType.OPENING_GAP,
		LocalTime.of(9, 0),
		LocalTime.of(9, 0),
		new BigDecimal("0.030000"),
		new BigDecimal("3.0000"));

	private final PriceMoveDetectionDto intraday = new PriceMoveDetectionDto(
		PriceMoveEventType.INTRADAY,
		LocalTime.of(9, 5),
		LocalTime.of(9, 10),
		new BigDecimal("0.043956"),
		new BigDecimal("3.5463"));

	private final LlmCallStats llmCallStats = new LlmCallStats();

	private FeedbackBatchService service;

	private static Instrument stock(Long id, String symbol, String name) {
		Instrument instrument = Instrument.create(
			Market.STOCK, symbol, name, BigDecimal.ONE, 10000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}

	@BeforeEach
	void setUp() {
		service = spy(new FeedbackBatchService(
			stockReplayService,
			instrumentService,
			priceMoveDetector,
			priceMoveCardService,
			marketBriefingService,
			instrumentNewsSummaryService,
			llmCallStats));
	}

	private void givenReadySessionWithTwoStocks() {
		when(stockReplayService.getCurrentReplaySession())
			.thenReturn(new StockReplaySessionDto(true, ORIGIN_TRADE_DATE));
		when(instrumentService.getRealInstrumentEntities(Market.STOCK))
			.thenReturn(List.of(instrumentA, instrumentB));
		when(stockReplayService.getFullDayCandles(anyLong(), any())).thenReturn(someCandles());
		when(stockReplayService.getPreviousTradingDayClose(anyLong(), any()))
			.thenReturn(Optional.of(new BigDecimal("9700")));
		when(priceMoveDetector.detect(any(), any())).thenReturn(List.of(gap, intraday));
		when(priceMoveCardService.confirmStockCard(any(), any(), any()))
			.thenReturn(Optional.of(mock(PriceMoveEvent.class)));
	}

	private static List<StockCandleDto> someCandles() {
		return List.of(new StockCandleDto(
			ORIGIN_TRADE_DATE,
			LocalTime.of(9, 0),
			new BigDecimal("10000"),
			new BigDecimal("10000"),
			new BigDecimal("10000"),
			new BigDecimal("10000"),
			1000L));
	}

	@Nested
	@DisplayName("배치 ② 재생세션이 READY가 아니면 아무것도 하지 않는다")
	class NotReady {

		@Test
		@DisplayName("세션이 준비되지 않았으면 탐지도 카드 확정도 하지 않고 예외도 없다")
		void doesNothingWhenTheReplaySessionIsNotReady() {
			when(stockReplayService.getCurrentReplaySession())
				.thenReturn(new StockReplaySessionDto(false, null));

			assertThatCode(() -> service.runPreMarketBatch()).doesNotThrowAnyException();

			verifyNoInteractions(priceMoveDetector);
			verifyNoInteractions(priceMoveCardService);
			verifyNoInteractions(instrumentService);
			verify(stockReplayService, never()).getFullDayCandles(anyLong(), any());
		}

		@Test
		@DisplayName("세션이 준비되지 않았으면 브리핑·요약 자리도 부르지 않는다")
		void doesNotEnterTheBriefingAndSummarySlotsWhenNotReady() {
			when(stockReplayService.getCurrentReplaySession())
				.thenReturn(new StockReplaySessionDto(false, null));

			service.runPreMarketBatch();

			verify(service, never()).generateMarketBriefing(any());
			verify(service, never()).generateNewsSummaries(any(), any(), any());
		}
	}

	@Nested
	@DisplayName("배치 ③ 생성 순서가 §C-6과 같다")
	class GenerationOrder {

		@Test
		@DisplayName("브리핑 → PRE_MARKET 요약 → 갭 카드 전 종목 → 장중 카드 전 종목 → FULL 요약")
		void followsTheGenerationOrderOfSectionC6() {
			givenReadySessionWithTwoStocks();
			List<Instrument> instruments = List.of(instrumentA, instrumentB);

			service.runPreMarketBatch();

			InOrder order = inOrder(service, priceMoveCardService);
			order.verify(service).generateMarketBriefing(ORIGIN_TRADE_DATE);
			order.verify(service)
				.generateNewsSummaries(instruments, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);
			order.verify(priceMoveCardService).confirmStockCard(instrumentA, ORIGIN_TRADE_DATE, gap);
			order.verify(priceMoveCardService).confirmStockCard(instrumentB, ORIGIN_TRADE_DATE, gap);
			order.verify(priceMoveCardService).confirmStockCard(instrumentA, ORIGIN_TRADE_DATE, intraday);
			order.verify(priceMoveCardService).confirmStockCard(instrumentB, ORIGIN_TRADE_DATE, intraday);
			order.verify(service)
				.generateNewsSummaries(instruments, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL);
		}

		@Test
		@DisplayName("브리핑이 카드 확정보다 먼저 불린다")
		void callsTheBriefingSlotBeforeAnyCardConfirmation() {
			givenReadySessionWithTwoStocks();

			service.runPreMarketBatch();

			InOrder order = inOrder(service, priceMoveCardService);
			order.verify(service).generateMarketBriefing(ORIGIN_TRADE_DATE);
			order.verify(priceMoveCardService, times(1)).confirmStockCard(instrumentA, ORIGIN_TRADE_DATE, gap);
		}

		@Test
		@DisplayName("두 요약이 같은 자리에서 한꺼번에 만들어지지 않는다 — FULL이 카드보다 뒤다")
		void createsFullSummaryAfterEveryCard() {
			givenReadySessionWithTwoStocks();

			service.runPreMarketBatch();

			InOrder order = inOrder(priceMoveCardService, service);
			order.verify(priceMoveCardService).confirmStockCard(instrumentB, ORIGIN_TRADE_DATE, intraday);
			order.verify(service).generateNewsSummaries(
				List.of(instrumentA, instrumentB), ORIGIN_TRADE_DATE, NewsSummaryScope.FULL);
		}

		@Test
		@DisplayName("종목당 탐지는 한 번만 돈다")
		void detectsOncePerInstrument() {
			givenReadySessionWithTwoStocks();

			service.runPreMarketBatch();

			verify(priceMoveDetector, times(2)).detect(any(), any());
		}
	}

	@Nested
	@DisplayName("배치 ① 분봉 조회 경로 (§코드 배치와 설정)")
	class CandleSource {

		@Test
		@DisplayName("하루치 분봉을 getFullDayCandles로 가져오고 getRevealedCandles를 쓰지 않는다")
		void readsFullDayCandlesAndNeverTheRevealedOnes() {
			givenReadySessionWithTwoStocks();

			service.runPreMarketBatch();

			verify(stockReplayService).getFullDayCandles(instrumentA.getId(), ORIGIN_TRADE_DATE);
			verify(stockReplayService).getFullDayCandles(instrumentB.getId(), ORIGIN_TRADE_DATE);
			verify(stockReplayService, never()).getRevealedCandles(anyLong(), any(), any());
		}

		@Test
		@DisplayName("직전 거래일 종가가 없으면 탐지기에 null을 넘긴다")
		void passesNullToTheDetectorWhenThereIsNoPreviousClose() {
			givenReadySessionWithTwoStocks();
			when(stockReplayService.getPreviousTradingDayClose(anyLong(), any()))
				.thenReturn(Optional.empty());

			service.runPreMarketBatch();

			ArgumentCaptor<BigDecimal> previousCloseCaptor = ArgumentCaptor.forClass(BigDecimal.class);
			verify(priceMoveDetector, times(2)).detect(any(), previousCloseCaptor.capture());
			assertThat(previousCloseCaptor.getAllValues()).containsOnlyNulls();
		}

		@Test
		@DisplayName("직전 거래일 종가가 있으면 그 값을 그대로 넘긴다")
		void passesThePreviousCloseToTheDetectorWhenItExists() {
			givenReadySessionWithTwoStocks();

			service.runPreMarketBatch();

			ArgumentCaptor<BigDecimal> previousCloseCaptor = ArgumentCaptor.forClass(BigDecimal.class);
			verify(priceMoveDetector, times(2)).detect(any(), previousCloseCaptor.capture());
			assertThat(previousCloseCaptor.getAllValues())
				.allSatisfy(value -> assertThat(value).isEqualByComparingTo("9700"));
		}
	}

	@Nested
	@DisplayName("배치 ④ 하나가 실패해도 나머지가 계속된다 (§실패 처리)")
	class FailureIsolation {

		@Test
		@DisplayName("한 종목의 카드 확정이 예외를 던져도 나머지 카드가 생기고 배치가 정상 종료한다")
		void continuesWithOtherCardsWhenOneConfirmationThrows() {
			givenReadySessionWithTwoStocks();
			when(priceMoveCardService.confirmStockCard(instrumentA, ORIGIN_TRADE_DATE, gap))
				.thenThrow(new IllegalStateException("LLM 호출 실패"));

			assertThatCode(() -> service.runPreMarketBatch()).doesNotThrowAnyException();

			verify(priceMoveCardService).confirmStockCard(instrumentB, ORIGIN_TRADE_DATE, gap);
			verify(priceMoveCardService).confirmStockCard(instrumentA, ORIGIN_TRADE_DATE, intraday);
			verify(priceMoveCardService).confirmStockCard(instrumentB, ORIGIN_TRADE_DATE, intraday);
		}

		@Test
		@DisplayName("한 종목의 탐지가 예외를 던져도 나머지 종목의 카드가 생기고 배치가 정상 종료한다")
		void continuesWithOtherInstrumentsWhenDetectionThrows() {
			givenReadySessionWithTwoStocks();
			when(stockReplayService.getFullDayCandles(instrumentA.getId(), ORIGIN_TRADE_DATE))
				.thenThrow(new IllegalStateException("분봉 조회 실패"));

			assertThatCode(() -> service.runPreMarketBatch()).doesNotThrowAnyException();

			verify(priceMoveCardService, never()).confirmStockCard(instrumentA, ORIGIN_TRADE_DATE, gap);
			verify(priceMoveCardService).confirmStockCard(instrumentB, ORIGIN_TRADE_DATE, gap);
			verify(priceMoveCardService).confirmStockCard(instrumentB, ORIGIN_TRADE_DATE, intraday);
		}

		@Test
		@DisplayName("브리핑 자리가 실패해도 카드 확정이 계속된다")
		void continuesWithCardsWhenTheBriefingSlotThrows() {
			givenReadySessionWithTwoStocks();
			doThrow(new IllegalStateException("브리핑 LLM 호출 실패"))
				.when(service).generateMarketBriefing(any());

			assertThatCode(() -> service.runPreMarketBatch()).doesNotThrowAnyException();

			verify(priceMoveCardService).confirmStockCard(instrumentA, ORIGIN_TRADE_DATE, gap);
			verify(priceMoveCardService).confirmStockCard(instrumentB, ORIGIN_TRADE_DATE, intraday);
		}

		@Test
		@DisplayName("한 종목의 요약 생성이 예외를 던져도 나머지 종목의 요약이 만들어진다")
		void continuesWithOtherInstrumentsWhenOneSummaryThrows() {
			givenReadySessionWithTwoStocks();
			when(instrumentNewsSummaryService
				.generateStockSummary(instrumentA, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET))
				.thenThrow(new IllegalStateException("요약 LLM 호출 실패"));

			assertThatCode(() -> service.runPreMarketBatch()).doesNotThrowAnyException();

			verify(instrumentNewsSummaryService)
				.generateStockSummary(instrumentB, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);
		}

		@Test
		@DisplayName("한 종목의 요약이 실패해도 카드와 FULL 요약까지 계속된다")
		void continuesThroughCardsAndFullSummariesWhenOneSummaryThrows() {
			givenReadySessionWithTwoStocks();
			when(instrumentNewsSummaryService
				.generateStockSummary(instrumentA, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET))
				.thenThrow(new IllegalStateException("요약 LLM 호출 실패"));

			assertThatCode(() -> service.runPreMarketBatch()).doesNotThrowAnyException();

			verify(priceMoveCardService).confirmStockCard(instrumentA, ORIGIN_TRADE_DATE, gap);
			verify(priceMoveCardService).confirmStockCard(instrumentB, ORIGIN_TRADE_DATE, intraday);
			verify(instrumentNewsSummaryService)
				.generateStockSummary(instrumentA, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL);
			verify(instrumentNewsSummaryService)
				.generateStockSummary(instrumentB, ORIGIN_TRADE_DATE, NewsSummaryScope.FULL);
		}

		@Test
		@DisplayName("브리핑 협력자가 예외를 던져도 요약과 카드가 계속된다")
		void continuesWithSummariesAndCardsWhenTheBriefingCollaboratorThrows() {
			givenReadySessionWithTwoStocks();
			when(marketBriefingService.generateStockBriefing(ORIGIN_TRADE_DATE))
				.thenThrow(new IllegalStateException("브리핑 LLM 호출 실패"));

			assertThatCode(() -> service.runPreMarketBatch()).doesNotThrowAnyException();

			verify(instrumentNewsSummaryService)
				.generateStockSummary(instrumentA, ORIGIN_TRADE_DATE, NewsSummaryScope.PRE_MARKET);
			verify(priceMoveCardService).confirmStockCard(instrumentA, ORIGIN_TRADE_DATE, gap);
		}

		@Test
		@DisplayName("PRE_MARKET 요약 자리가 실패해도 카드 확정이 계속된다")
		void continuesWithCardsWhenThePreMarketSummarySlotThrows() {
			givenReadySessionWithTwoStocks();
			doThrow(new IllegalStateException("요약 LLM 호출 실패"))
				.when(service)
				.generateNewsSummaries(any(), any(), any());

			assertThatCode(() -> service.runPreMarketBatch()).doesNotThrowAnyException();

			verify(priceMoveCardService).confirmStockCard(instrumentA, ORIGIN_TRADE_DATE, gap);
			verify(priceMoveCardService).confirmStockCard(instrumentB, ORIGIN_TRADE_DATE, intraday);
		}
	}

	@Nested
	@DisplayName("배치 ⑤ 두 번 실행해도 중복 생성되지 않는다")
	class RepeatedRun {

		@Test
		@DisplayName("두 번 실행하면 같은 인자로 두 번 위임할 뿐 배치가 상태를 만들지 않는다")
		void delegatesTwiceWithTheSameArgumentsWithoutKeepingState() {
			givenReadySessionWithTwoStocks();

			service.runPreMarketBatch();
			service.runPreMarketBatch();

			verify(priceMoveCardService, times(2)).confirmStockCard(instrumentA, ORIGIN_TRADE_DATE, gap);
			verify(priceMoveCardService, times(2))
				.confirmStockCard(instrumentB, ORIGIN_TRADE_DATE, intraday);
		}

		@Test
		@DisplayName("카드가 이미 있어 empty()가 와도 배치는 정상 종료한다")
		void finishesNormallyWhenEveryConfirmationReturnsEmpty() {
			givenReadySessionWithTwoStocks();
			when(priceMoveCardService.confirmStockCard(any(), any(), any())).thenReturn(Optional.empty());

			assertThatCode(() -> service.runPreMarketBatch()).doesNotThrowAnyException();
		}
	}

	@Test
	@DisplayName("단계 격리를 뚫고 나온 실패에도 LLM 계측 스코프가 닫힌다")
	void closesTheLlmScopeEvenWhenAFailureEscapesTheStepIsolation() {
		givenReadySessionWithTwoStocks();
		when(stockReplayService.getFullDayCandles(anyLong(), any())).thenThrow(new StackOverflowError("boom"));

		assertThatThrownBy(() -> service.runPreMarketBatch()).isInstanceOf(StackOverflowError.class);

		llmCallStats.record(5_000_000L);
		assertThat(llmCallStats.finishScope().count()).isZero();
	}

	@Test
	@DisplayName("원본 거래일은 재생세션이 준 값을 그대로 쓴다")
	void usesTheOriginTradeDateGivenByTheReplaySession() {
		givenReadySessionWithTwoStocks();

		service.runPreMarketBatch();

		verify(stockReplayService).getFullDayCandles(instrumentA.getId(), ORIGIN_TRADE_DATE);
		verify(stockReplayService, never()).getFullDayCandles(anyLong(), eq(SERVICE_DATE));
	}
}
