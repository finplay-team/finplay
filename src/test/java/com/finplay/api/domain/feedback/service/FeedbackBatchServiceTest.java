// 개장 전 배치가 spec 012 §C-6의 생성 순서를 지키고 실패를 단계별로 격리하는지 검증하는 단위 테스트다.
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

// 테스트가 같은 패키지에 있어야 package-private인 두 빈 자리(generateMarketBriefing·generateNewsSummaries)를
// spy로 볼 수 있다 — 생성 순서(배치 ③)가 이 이슈의 완료 조건이라 그 자리를 단정할 수 있어야 한다.
//
// 배치 ①(하루치 분봉을 실제로 받아온다)과 원장 불변은 실 DB 종단이 필요하므로
// FeedbackBatchIntegrationTest가 맡는다. 여기서는 "어떤 조회를 쓰는가"까지만 본다.
class FeedbackBatchServiceTest {

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 6);

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 5);

	private final StockReplayService stockReplayService = mock(StockReplayService.class);

	private final InstrumentService instrumentService = mock(InstrumentService.class);

	private final PriceMoveDetector priceMoveDetector = mock(PriceMoveDetector.class);

	private final PriceMoveCardService priceMoveCardService = mock(PriceMoveCardService.class);

	// 두 빈 자리의 본문이 부르는 협력자다. 여기서는 순서·격리만 보므로 기본 stub(Optional.empty())으로 둔다 —
	// 실제 생성 규칙은 각 서비스의 단위 테스트와 배치 통합 테스트가 맡는다.
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

	// 이슈 #198의 계측 수집기. 스코프가 실제로 닫혔는지 보려면 배치가 쓴 것과 같은 인스턴스여야 한다.
	private final LlmCallStats llmCallStats = new LlmCallStats();

	private FeedbackBatchService service;

	private static Instrument stock(Long id, String symbol, String name) {
		Instrument instrument = Instrument.create(
			Market.STOCK, symbol, name, BigDecimal.ONE, 10000L, true, LocalDateTime.now());
		// 저장 전 엔티티라 id가 null이다 — 배치가 instrument.getId()로 조회하므로 값을 넣어 준다.
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}

	@BeforeEach
	void setUp() {
		// spy여야 package-private 빈 자리 두 개의 호출 순서를 볼 수 있다. Mockito spy는 프록시가 this이므로
		// runPreMarketBatch 안의 자기호출도 그대로 잡힌다.
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

		// 상태가 무엇이든 getCurrentReplaySession이 (false, null)로 접어 주므로 배치가 보는 것은 그 하나다.
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

		// 빈 자리 두 개도 불리면 안 된다 — 5번이 본문을 채우면 그때부터 미준비 상태에서 LLM이 돌게 된다.
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

		// 종목이 1개면 "종목 루프 안에서 갭·장중을 함께 만드는" 구현도 통과한다. 2개를 심어야
		// A-갭 → B-갭 → A-장중 → B-장중 순서가 강제되고, 그 회귀는 A-장중이 B-갭보다 앞서 실패한다.
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

		// 브리핑은 09:00 정각에 READY여야 하는 산출물이라 가장 먼저다 — 카드보다 뒤로 가면 LLM 약 81건이
		// 앞에 쌓여 개장 직후에 브리핑이 빈다.
		@Test
		@DisplayName("브리핑이 카드 확정보다 먼저 불린다")
		void callsTheBriefingSlotBeforeAnyCardConfirmation() {
			givenReadySessionWithTwoStocks();

			service.runPreMarketBatch();

			InOrder order = inOrder(service, priceMoveCardService);
			order.verify(service).generateMarketBriefing(ORIGIN_TRADE_DATE);
			order.verify(priceMoveCardService, times(1)).confirmStockCard(instrumentA, ORIGIN_TRADE_DATE, gap);
		}

		// FULL 요약은 15:30 이후에만 쓰이므로 마지막이다 — 앞으로 당기면 09:00에 노출돼 요약 한 문장이
		// 그날 오후를 통째로 알려준다(FEED-008).
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

		// 종목마다 두 번 탐지하면 같은 계산을 두 배로 한다 — 한 번 계산해 두 단계가 나눠 쓰는 형태다.
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

		// getRevealedCandles는 08:45에 항상 빈 목록이고 그것이 정상 응답이라, 쓰면 카드가 매일 0건이 되어도
		// 로그조차 남지 않는다. 실제로 행이 생기는지는 FeedbackBatchIntegrationTest가 단정한다.
		@Test
		@DisplayName("하루치 분봉을 getFullDayCandles로 가져오고 getRevealedCandles를 쓰지 않는다")
		void readsFullDayCandlesAndNeverTheRevealedOnes() {
			givenReadySessionWithTwoStocks();

			service.runPreMarketBatch();

			verify(stockReplayService).getFullDayCandles(instrumentA.getId(), ORIGIN_TRADE_DATE);
			verify(stockReplayService).getFullDayCandles(instrumentB.getId(), ORIGIN_TRADE_DATE);
			verify(stockReplayService, never()).getRevealedCandles(anyLong(), any(), any());
		}

		// 직전 종가가 없으면 갭 카드만 생략된다 — 탐지기와의 계약이 null이므로 Optional을 그대로 넘기면 안 된다.
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

		// §C-6 — LLM 호출은 약 81건(카드 ~48 + 요약 32 + 브리핑 1)이고 "호출 하나가 실패해도 다음으로
		// 넘어간다"가 배치 ④의 문장이다. 브리핑은 그 81건 중 하나이고, 지금은 빈 자리라 던질 수 없지만
		// plan.md 5번이 본문을 채우는 순간 실패 경로가 열린다. 카드가 그 실패에 딸려 죽으면 안 된다.
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

		// 위 둘은 메서드 경계를 spy로 갈아끼운 형태라 "본문이 종목마다 접는가"를 볼 수 없다. 아래 둘은 본문이
		// 실제로 부르는 협력자를 특정 종목에서만 던지게 한다 — continuesWithOtherInstrumentsWhenDetectionThrows와
		// 같은 형태이며, 격리를 호출부 try/catch에만 두면 여기서 깨진다.
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

		// 한 종목의 요약이 터진 날 그 뒤 단계가 통째로 날아가면 그날 카드가 전부 사라진다 — PRE_MARKET 요약이
		// 카드보다 앞이라 그렇다. 예외도 없이 조용히 비므로 단정으로만 고정된다.
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

		// 브리핑은 호출 1건이라 본문에 추가 격리가 없다 — 단계 단위 try/catch가 유일한 방어선이므로 협력자가
		// 실제로 던졌을 때 뒤 단계가 사는지를 여기서 본다.
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

		// 중복 방지의 본체는 4번(PriceMoveCardService의 선판정 + 유니크 제약)이다. 배치 쪽에서 볼 것은
		// 두 번째 실행도 같은 인자로 같은 횟수만 부르고 스스로 상태를 들고 있지 않다는 것이다.
		// 실제로 행이 늘지 않는지는 FeedbackBatchIntegrationTest가 단정한다.
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

	// 이슈 #198 · PR #202 리뷰 권장 ① — 단계별 격리를 뚫고 나온 실패에서도 계측 스코프는 닫혀야 한다.
	// 열린 채로 나가면 스케줄 풀의 그 워커 스레드에 스코프가 다음 배치까지 남는다.
	@Test
	@DisplayName("단계 격리를 뚫고 나온 실패에도 LLM 계측 스코프가 닫힌다")
	void closesTheLlmScopeEvenWhenAFailureEscapesTheStepIsolation() {
		givenReadySessionWithTwoStocks();
		// 단계별 catch가 RuntimeException만 잡으므로 Error는 배치 밖으로 나간다 — 스코프가 열린 채 나가는 경로다.
		when(stockReplayService.getFullDayCandles(anyLong(), any())).thenThrow(new StackOverflowError("boom"));

		assertThatThrownBy(() -> service.runPreMarketBatch()).isInstanceOf(StackOverflowError.class);

		// 스코프가 남아 있었다면 이 record()가 집계돼 1건이 된다. 닫혔으면 스코프 밖 호출이라 무시된다.
		llmCallStats.record(5_000_000L);
		assertThat(llmCallStats.finishScope().count()).isZero();
	}

	// 배치가 서비스 날짜를 스스로 계산하지 않는다 — 원본 거래일은 세션이 준 값 하나뿐이다.
	@Test
	@DisplayName("원본 거래일은 재생세션이 준 값을 그대로 쓴다")
	void usesTheOriginTradeDateGivenByTheReplaySession() {
		givenReadySessionWithTwoStocks();

		service.runPreMarketBatch();

		verify(stockReplayService).getFullDayCandles(instrumentA.getId(), ORIGIN_TRADE_DATE);
		verify(stockReplayService, never()).getFullDayCandles(anyLong(), eq(SERVICE_DATE));
	}
}
