// KisHistoricalCandleCollector의 08:10 KST 배치가 정상 저장·전체오류·부분오류·종목별 조회 실패·재실행 멱등·샌드박스 종목 제외를 올바르게 처리하는지, KisHistoricalCandleImportWriter가 실패 결과를 로그로 남기는지 검증하는 단위 테스트
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finplay.api.market.domain.ImportStatus;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.MarketDataImport;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.MarketDataImportRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class KisHistoricalCandleCollectorTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 2026-07-30(목) 08:10 KST — 직전 영업일은 주말·공휴일 없이 2026-07-29(수).
	private static final LocalDateTime WEEKDAY_RUN_AT = LocalDateTime.of(2026, 7, 30, 8, 10, 0);
	private static final LocalDate EXPECTED_TRADING_DATE = LocalDate.of(2026, 7, 29);

	private final InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
	private final StockCandleRepository stockCandleRepository = mock(StockCandleRepository.class);
	private final MarketDataImportRepository marketDataImportRepository = mock(MarketDataImportRepository.class);
	// 트랜잭션 경계 분리(PR #94 리뷰 권장사항 ②·④) 이후 저장은 이 컴포넌트를 거친다 — 단위 테스트는 Spring 컨텍스트 없이
	// 직접 생성해 같은 mock repository로 위임하므로 기존 verify(marketDataImportRepository)·verify(stockCandleRepository)
	// 검증은 그대로 유효하다.
	private final KisHistoricalCandleImportWriter importWriter = new KisHistoricalCandleImportWriter(
		stockCandleRepository, marketDataImportRepository);

	// COLLECT-STAB-001 — collect()가 이 락을 못 얻으면 조용히 반환하므로, 락과 무관한 기존 시나리오(전체/부분
	// 실패·멱등성·샌드박스 제외 등)가 계속 성립하려면 기본적으로 항상 락 획득에 성공해야 한다. 락 자체의 동작
	// (실패 시 조용히 반환·finally 해제 보장)은 별도 테스트에서 이 mock을 개별적으로 재구성해 검증한다.
	private final StockCollectionLock stockCollectionLock = mock(StockCollectionLock.class);

	{
		when(stockCollectionLock.tryLock(any())).thenReturn(Optional.of("test-lock-token"));
	}

	private static Clock fixedClock(LocalDateTime dateTime) {
		return Clock.fixed(dateTime.atZone(KST).toInstant(), KST);
	}

	private static Instrument stockInstrument(long id, String symbol) {
		Instrument instrument = Instrument.create(
			Market.STOCK, symbol, symbol + "종목", BigDecimal.ONE, 70000L, true, LocalDateTime.now());
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}

	private static RawMinuteCandleDto validCandle(LocalTime time, String price) {
		BigDecimal p = new BigDecimal(price);
		return new RawMinuteCandleDto(time, p, p, p, p, 100L);
	}

	// KisHistoricalCandleClient가 응답 파싱 실패 등으로 예외를 던지는 상황을 흉내내는 테스트 전용 더블.
	// FakeKisHistoricalCandleClient는 실패를 표현할 수 없어(항상 정상 리스트 반환) 이 시나리오 전용으로 따로 둔다.
	private static class ThrowingKisHistoricalCandleClient implements KisHistoricalCandleClient {
		@Override
		public List<RawMinuteCandleDto> fetchMinuteCandles(String symbol, LocalDate tradingDate) {
			throw new IllegalStateException("응답 파싱 실패: 지원하지 않는 응답 구조");
		}
	}

	@Test
	void collectSavesAllCandlesAndRecordsSuccessWhenAllInstrumentsValid() {
		Instrument instrumentA = stockInstrument(1L, "005930");
		Instrument instrumentB = stockInstrument(2L, "000660");
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(instrumentA, instrumentB));
		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(anyLong(), eq(EXPECTED_TRADING_DATE)))
			.thenReturn(false);

		FakeKisHistoricalCandleClient fakeClient = new FakeKisHistoricalCandleClient();
		fakeClient.setCandles("005930", List.of(
			validCandle(LocalTime.of(9, 0), "70000"), validCandle(LocalTime.of(9, 1), "70100")));
		fakeClient.setCandles("000660", List.of(validCandle(LocalTime.of(9, 0), "120000")));

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, fakeClient, stockCandleRepository, importWriter,
			fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		collector.collect();

		ArgumentCaptor<List<StockCandle>> savedCandlesCaptor = ArgumentCaptor.forClass(List.class);
		verify(stockCandleRepository, times(2)).saveAll(savedCandlesCaptor.capture());
		List<StockCandle> allSaved = savedCandlesCaptor.getAllValues().stream().flatMap(List::stream).toList();
		assertThat(allSaved).hasSize(3);
		assertThat(allSaved).allSatisfy(candle -> assertThat(candle.getTradingDate()).isEqualTo(EXPECTED_TRADING_DATE));

		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository).save(importCaptor.capture());
		MarketDataImport savedImport = importCaptor.getValue();
		assertThat(savedImport.getStatus()).isEqualTo(ImportStatus.SUCCESS);
		assertThat(savedImport.getFailureReason()).isNull();
		assertThat(savedImport.getSourceTradingDate()).isEqualTo(EXPECTED_TRADING_DATE);
		assertThat(savedImport.getCollectedAt()).isEqualTo(WEEKDAY_RUN_AT);
	}

	@Test
	void collectRecordsSuccessWithNoFailureReasonWhenNoInstrumentsAreFound() {
		// COLLECT-STAB-002 완료 조건 — 샌드박스 종목만 있어 조회 대상이 0건이면 KIS를 호출하지 않고, market_data_imports에
		// 실패 이력이 아니라 SUCCESS(failureReason=null)로 남아야 한다. 대상이 0건인 것 자체는 오류가 아니다.
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK)).thenReturn(List.of());
		KisHistoricalCandleClient neverCalledClient = mock(KisHistoricalCandleClient.class);

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, neverCalledClient, stockCandleRepository, importWriter,
			fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		collector.collect();

		verify(neverCalledClient, never()).fetchMinuteCandles(any(), any());
		verify(stockCandleRepository, never()).saveAll(any());
		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository).save(importCaptor.capture());
		MarketDataImport savedImport = importCaptor.getValue();
		assertThat(savedImport.getStatus()).isEqualTo(ImportStatus.SUCCESS);
		assertThat(savedImport.getFailureReason()).isNull();
	}

	@Test
	void collectSavesNothingAndRecordsFailedWhenClientThrowsForEveryInstrument() {
		// KisHistoricalCandleClient.fetchMinuteCandles가 예외를 던져도 각 종목 하나만의 실패로 흡수된다(PR #94 리뷰
		// 권장사항 ③) — 두 종목 모두 실패해 결과적으로 succeededOutcomes가 비므로 FAILED로 기록되지만, 이는 종목별 실패
		// 목록을 모은 결과이지 "전체 응답 오류"로 collect()의 최상위 catch가 개입한 결과가 아니다.
		Instrument instrumentA = stockInstrument(1L, "005930");
		Instrument instrumentB = stockInstrument(2L, "000660");
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(instrumentA, instrumentB));
		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(anyLong(), eq(EXPECTED_TRADING_DATE)))
			.thenReturn(false);

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, new ThrowingKisHistoricalCandleClient(), stockCandleRepository,
			importWriter, fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		collector.collect();

		verify(stockCandleRepository, never()).saveAll(any());
		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository).save(importCaptor.capture());
		MarketDataImport savedImport = importCaptor.getValue();
		assertThat(savedImport.getStatus()).isEqualTo(ImportStatus.FAILED);
		assertThat(savedImport.getFailureReason()).contains("005930").contains("000660");
		assertThat(savedImport.getSourceTradingDate()).isEqualTo(EXPECTED_TRADING_DATE);
	}

	@Test
	void collectSkipsOnlyInstrumentWhoseFetchThrowsAndRecordsPartialSuccess() {
		// 종목 하나만 KIS 호출이 일시적으로 실패해도(타임아웃 등) 나머지 종목의 정상 결과는 버려지지 않아야 한다
		// (PR #94 리뷰 권장사항 ③의 핵심 시나리오 — 16종 중 1종만 실패해도 전체가 FAILED가 되던 것을 고친다).
		Instrument healthyInstrument = stockInstrument(1L, "005930");
		Instrument flakyInstrument = stockInstrument(2L, "000660");
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(healthyInstrument, flakyInstrument));
		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(anyLong(), eq(EXPECTED_TRADING_DATE)))
			.thenReturn(false);

		KisHistoricalCandleClient partiallyFlakyClient = (symbol, tradingDate) -> {
			if ("000660".equals(symbol)) {
				throw new RuntimeException("연결이 재설정되었습니다");
			}
			return List.of(validCandle(LocalTime.of(9, 0), "70000"));
		};

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, partiallyFlakyClient, stockCandleRepository, importWriter,
			fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		collector.collect();

		ArgumentCaptor<List<StockCandle>> savedCandlesCaptor = ArgumentCaptor.forClass(List.class);
		verify(stockCandleRepository, times(1)).saveAll(savedCandlesCaptor.capture());
		assertThat(savedCandlesCaptor.getValue()).hasSize(1);
		assertThat(savedCandlesCaptor.getValue().get(0).getInstrument().getSymbol()).isEqualTo("005930");

		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository).save(importCaptor.capture());
		MarketDataImport savedImport = importCaptor.getValue();
		assertThat(savedImport.getStatus()).isEqualTo(ImportStatus.PARTIAL_SUCCESS);
		assertThat(savedImport.getFailureReason()).contains("000660");
	}

	@Test
	void collectRecordsFailedInSeparateTransactionWhenFailureIsNotAttributableToAnyInstrument() {
		// 종목 목록은 있지만 검증·저장 로직 자체에서(여기서는 marketDataImportRepository.save 실패로 흉내) 예외가 나면
		// 종목 하나로 좁힐 수 없는 진짜 전체 오류다 — collect()의 최상위 catch가 recordFailedImport(REQUIRES_NEW 취지의
		// 별도 호출, PR #94 리뷰 권장사항 ④)로 FAILED 이력을 남겨야 한다.
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK)).thenReturn(List.of());
		when(marketDataImportRepository.save(any()))
			.thenThrow(new RuntimeException("DB 저장 중 오류"))
			.thenAnswer(invocation -> invocation.getArgument(0));

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, new FakeKisHistoricalCandleClient(), stockCandleRepository, importWriter,
			fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		collector.collect();

		verify(stockCandleRepository, never()).saveAll(any());
		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository, times(2)).save(importCaptor.capture());
		MarketDataImport recordedFailure = importCaptor.getAllValues().get(1);
		assertThat(recordedFailure.getStatus()).isEqualTo(ImportStatus.FAILED);
		assertThat(recordedFailure.getFailureReason()).contains("예상치 못한 오류");
		assertThat(recordedFailure.getSourceTradingDate()).isEqualTo(EXPECTED_TRADING_DATE);
	}

	@Test
	void collectSkipsOnlyBrokenInstrumentAndRecordsPartialSuccessWithReason() {
		Instrument validInstrument = stockInstrument(1L, "005930");
		Instrument brokenInstrument = stockInstrument(2L, "000660");
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(validInstrument, brokenInstrument));
		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(anyLong(), eq(EXPECTED_TRADING_DATE)))
			.thenReturn(false);

		FakeKisHistoricalCandleClient fakeClient = new FakeKisHistoricalCandleClient();
		fakeClient.setCandles("005930", List.of(validCandle(LocalTime.of(9, 0), "70000")));
		// 동일 분봉시각 중복 — 구조 오류로 이 종목만 미저장 대상이 되어야 한다.
		fakeClient.setCandles("000660", List.of(
			validCandle(LocalTime.of(9, 0), "120000"), validCandle(LocalTime.of(9, 0), "120100")));

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, fakeClient, stockCandleRepository, importWriter,
			fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		collector.collect();

		ArgumentCaptor<List<StockCandle>> savedCandlesCaptor = ArgumentCaptor.forClass(List.class);
		verify(stockCandleRepository, times(1)).saveAll(savedCandlesCaptor.capture());
		List<StockCandle> saved = savedCandlesCaptor.getValue();
		assertThat(saved).hasSize(1);
		assertThat(saved.get(0).getInstrument().getSymbol()).isEqualTo("005930");

		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository).save(importCaptor.capture());
		MarketDataImport savedImport = importCaptor.getValue();
		assertThat(savedImport.getStatus()).isEqualTo(ImportStatus.PARTIAL_SUCCESS);
		assertThat(savedImport.getFailureReason()).contains("000660");
	}

	@Test
	void collectIsIdempotentAndDoesNotRefetchOrResaveAlreadyCollectedInstrumentOnRerun() {
		Instrument instrument = stockInstrument(1L, "005930");
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(instrument));

		FakeKisHistoricalCandleClient fakeClient = new FakeKisHistoricalCandleClient();
		fakeClient.setCandles("005930", List.of(validCandle(LocalTime.of(9, 0), "70000")));
		KisHistoricalCandleClient spyClient = spy(fakeClient);

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, spyClient, stockCandleRepository, importWriter,
			fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		// 1차 실행: 아직 저장된 분봉이 없다.
		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(1L, EXPECTED_TRADING_DATE))
			.thenReturn(false);
		collector.collect();

		// 2차 실행(재실행): 이미 저장된 분봉이 있다고 가정한다 — UNIQUE 제약 기반 멱등 재실행 시나리오.
		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(1L, EXPECTED_TRADING_DATE))
			.thenReturn(true);
		collector.collect();

		// 종목 조회(fetchMinuteCandles)는 최초 1회만 일어나야 한다 — 재실행에서는 다시 조회하지 않는다.
		verify(spyClient, times(1)).fetchMinuteCandles(eq("005930"), eq(EXPECTED_TRADING_DATE));
		// 저장도 최초 1회만 일어나야 한다.
		verify(stockCandleRepository, times(1)).saveAll(any());
		// 두 번 모두 수집 이력은 남되(SUCCESS), 두 번째 실행은 신규 저장 없이도 SUCCESS다(미검증 오류 없음).
		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository, times(2)).save(importCaptor.capture());
		assertThat(importCaptor.getAllValues())
			.extracting(MarketDataImport::getStatus)
			.containsExactly(ImportStatus.SUCCESS, ImportStatus.SUCCESS);
	}

	@Test
	void collectNeverDependsOnStockReplaySessionRepository() {
		// tasks.md 요구: "StockReplaySession을 직접 생성·수정하지 않는다". 이 컬렉터가 해당 레포지토리를 아예 주입받지
		// 않는다는 것을 구조적으로 고정해, 향후 누군가 실수로 의존성을 추가해도 이 테스트가 회귀를 잡아낸다.
		Field[] fields = KisHistoricalCandleCollector.class.getDeclaredFields();
		boolean referencesStockReplaySessionRepository = Arrays.stream(fields)
			.anyMatch(field -> field.getType().getSimpleName().equals("StockReplaySessionRepository"));

		assertThat(referencesStockReplaySessionRepository).isFalse();
	}

	@Test
	void collectResolvesPreviousBusinessDaySkippingWeekendAt0810KstClock() {
		// 2026-08-03(월) 08:10 KST 실행 — 주말(08-01 토, 08-02 일)을 건너뛰어 직전 영업일은 2026-07-31(금)이어야 한다.
		LocalDateTime mondayRunAt = LocalDateTime.of(2026, 8, 3, 8, 10, 0);
		LocalDate expectedFriday = LocalDate.of(2026, 7, 31);

		Instrument instrument = stockInstrument(1L, "005930");
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(instrument));
		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(1L, expectedFriday)).thenReturn(false);

		FakeKisHistoricalCandleClient fakeClient = new FakeKisHistoricalCandleClient();
		fakeClient.setCandles("005930", List.of(validCandle(LocalTime.of(9, 0), "70000")));

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, fakeClient, stockCandleRepository, importWriter,
			fixedClock(mondayRunAt), new BusinessDayCalendar(), stockCollectionLock);

		collector.collect();

		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository).save(importCaptor.capture());
		assertThat(importCaptor.getValue().getSourceTradingDate()).isEqualTo(expectedFriday);
		assertThat(importCaptor.getValue().getCollectedAt()).isEqualTo(mondayRunAt);
	}

	@Test
	void collectResolvesPreviousBusinessDaySkippingHolidayAndWeekendTogether() {
		// 2026-08-18(화) 08:10 KST 실행 — 08-17(월, 공휴일)·08-16(일)·08-15(토, 공휴일)을 모두 건너뛰어
		// 직전 영업일은 2026-08-14(금)이어야 한다(holidays-2026.txt에 08-15·08-17 등재).
		LocalDateTime tuesdayRunAt = LocalDateTime.of(2026, 8, 18, 8, 10, 0);
		LocalDate expectedFriday = LocalDate.of(2026, 8, 14);

		Instrument instrument = stockInstrument(1L, "005930");
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(instrument));
		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(1L, expectedFriday)).thenReturn(false);

		FakeKisHistoricalCandleClient fakeClient = new FakeKisHistoricalCandleClient();
		fakeClient.setCandles("005930", List.of(validCandle(LocalTime.of(9, 0), "70000")));

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, fakeClient, stockCandleRepository, importWriter,
			fixedClock(tuesdayRunAt), new BusinessDayCalendar(), stockCollectionLock);

		collector.collect();

		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository).save(importCaptor.capture());
		assertThat(importCaptor.getValue().getSourceTradingDate()).isEqualTo(expectedFriday);
	}

	@Test
	void collectProcessesOnlyRealInstrumentsWhenTutorialSampleInstrumentsAreExcludedByRepositoryQuery() {
		// collect()는 findByMarketAndTutorialSampleFalseOrderByIdAsc로만 종목을 조회해야 한다(COLLECT-STAB-002). 옛
		// 메서드(findByMarketOrderByIdAsc)를 실수로 다시 호출하도록 되돌아가면 그 메서드가 반환하는 샌드박스 종목
		// (SANDBOX_STK_1, 종목코드 형식이 6자리 숫자가 아님)까지 검증 단계에 도달해 fetchMinuteCandles가 호출되고
		// PARTIAL_SUCCESS로 이어진다 — 이 테스트는 그 회귀를 잡는다.
		Instrument realInstrument = stockInstrument(1L, "005930");
		Instrument sandboxInstrument = stockInstrument(2L, "SANDBOX_STK_1");
		ReflectionTestUtils.setField(sandboxInstrument, "tutorialSample", true);
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(realInstrument));
		when(instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(realInstrument, sandboxInstrument));
		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(anyLong(), eq(EXPECTED_TRADING_DATE)))
			.thenReturn(false);

		FakeKisHistoricalCandleClient fakeClient = new FakeKisHistoricalCandleClient();
		fakeClient.setCandles("005930", List.of(validCandle(LocalTime.of(9, 0), "70000")));
		KisHistoricalCandleClient spyClient = spy(fakeClient);

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, spyClient, stockCandleRepository, importWriter,
			fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		collector.collect();

		verify(spyClient, never()).fetchMinuteCandles(eq("SANDBOX_STK_1"), any());
		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository).save(importCaptor.capture());
		assertThat(importCaptor.getValue().getStatus()).isEqualTo(ImportStatus.SUCCESS);
	}

	@Test
	void persistLogsWarnWithStatusAndReasonWhenResultIsPartialSuccess() {
		// COLLECT-STAB-004 — 부분 실패는 market_data_imports를 직접 조회하지 않아도 로그에서 검색 가능해야 한다.
		Instrument healthyInstrument = stockInstrument(1L, "005930");
		Instrument flakyInstrument = stockInstrument(2L, "000660");
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(healthyInstrument, flakyInstrument));
		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(anyLong(), eq(EXPECTED_TRADING_DATE)))
			.thenReturn(false);

		KisHistoricalCandleClient partiallyFlakyClient = (symbol, tradingDate) -> {
			if ("000660".equals(symbol)) {
				throw new RuntimeException("연결이 재설정되었습니다");
			}
			return List.of(validCandle(LocalTime.of(9, 0), "70000"));
		};
		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, partiallyFlakyClient, stockCandleRepository, importWriter,
			fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		List<ILoggingEvent> logs = capturingLogs(collector::collect);

		assertThat(logs)
			.extracting(ILoggingEvent::getFormattedMessage)
			.anyMatch(message -> message.contains("PARTIAL_SUCCESS") && message.contains("000660"));
		assertThat(logs.stream().filter(event -> event.getFormattedMessage().contains("PARTIAL_SUCCESS")))
			.allMatch(event -> event.getLevel() == Level.WARN);
	}

	@Test
	void persistLogsErrorWithStatusAndReasonWhenResultIsFailed() {
		// COLLECT-STAB-004 — 전체 실패(FAILED)는 부분 실패보다 심각도가 높으므로 warn이 아니라 error로 남는다.
		Instrument instrumentA = stockInstrument(1L, "005930");
		Instrument instrumentB = stockInstrument(2L, "000660");
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(instrumentA, instrumentB));
		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(anyLong(), eq(EXPECTED_TRADING_DATE)))
			.thenReturn(false);

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, new ThrowingKisHistoricalCandleClient(), stockCandleRepository,
			importWriter, fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		List<ILoggingEvent> logs = capturingLogs(collector::collect);

		assertThat(logs)
			.extracting(ILoggingEvent::getFormattedMessage)
			.anyMatch(message -> message.contains("FAILED"));
		assertThat(logs.stream().filter(event -> event.getFormattedMessage().contains("FAILED")))
			.allMatch(event -> event.getLevel() == Level.ERROR);
	}

	@Test
	void persistDoesNotLogWarnOrErrorWhenResultIsSuccess() {
		Instrument instrumentA = stockInstrument(1L, "005930");
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(instrumentA));
		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(anyLong(), eq(EXPECTED_TRADING_DATE)))
			.thenReturn(false);

		FakeKisHistoricalCandleClient fakeClient = new FakeKisHistoricalCandleClient();
		fakeClient.setCandles("005930", List.of(validCandle(LocalTime.of(9, 0), "70000")));

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, fakeClient, stockCandleRepository, importWriter,
			fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		List<ILoggingEvent> logs = capturingLogs(collector::collect);

		assertThat(logs).noneMatch(event -> event.getLevel() == Level.WARN || event.getLevel() == Level.ERROR);
	}

	@Test
	void collectSkipsEntirelyWithoutCallingKisOrRecordingImportWhenLockIsNotAcquired() {
		// COLLECT-STAB-001 비즈니스 규칙 — 락 획득 실패는 "시도했으나 실패"가 아니라 "이번 실행 자체가 일어나지
		// 않음"이다. KIS 호출도, market_data_imports 저장도 전혀 일어나지 않아야 한다(다른 실행이 처리 중이라고
		// 본다 — 실패 이력으로 기록하지 않는다).
		when(stockCollectionLock.tryLock(EXPECTED_TRADING_DATE)).thenReturn(Optional.empty());
		KisHistoricalCandleClient neverCalledClient = mock(KisHistoricalCandleClient.class);
		InstrumentRepository neverCalledInstrumentRepository = mock(InstrumentRepository.class);

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			neverCalledInstrumentRepository, neverCalledClient, stockCandleRepository, importWriter,
			fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		collector.collect();

		verify(neverCalledInstrumentRepository, never()).findByMarketAndTutorialSampleFalseOrderByIdAsc(any());
		verify(neverCalledClient, never()).fetchMinuteCandles(any(), any());
		verify(marketDataImportRepository, never()).save(any());
		verify(stockCollectionLock, never()).unlock(any(), any());
	}

	@Test
	void collectReleasesLockWithTheAcquiredTokenEvenWhenATopLevelFailureOccurs() {
		// try/finally 해제 보장 — 종목 하나로 좁힐 수 없는 전체 오류(여기서는 marketDataImportRepository.save 실패)로
		// collect()가 예외 경로를 타도 락은 반드시 해제되어야 한다. 그렇지 않으면 다음 실행이 TTL 만료 전까지 계속
		// 건너뛰게 된다.
		when(stockCollectionLock.tryLock(EXPECTED_TRADING_DATE)).thenReturn(Optional.of("held-token"));
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK)).thenReturn(List.of());
		when(marketDataImportRepository.save(any()))
			.thenThrow(new RuntimeException("DB 저장 중 오류"))
			.thenAnswer(invocation -> invocation.getArgument(0));

		KisHistoricalCandleCollector collector = new KisHistoricalCandleCollector(
			instrumentRepository, new FakeKisHistoricalCandleClient(), stockCandleRepository, importWriter,
			fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);

		collector.collect();

		verify(stockCollectionLock).unlock(EXPECTED_TRADING_DATE, "held-token");
	}

	// 로그가 부분/전체 실패 구분의 유일한 외부 관찰점이라 로거에 임시 appender를 붙인다(RankingRebuildServiceTest와 같은 방식).
	private static List<ILoggingEvent> capturingLogs(Runnable action) {
		Logger logger = (Logger)LoggerFactory.getLogger(KisHistoricalCandleImportWriter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level originalLevel = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			action.run();
			return List.copyOf(appender.list);
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(originalLevel);
			appender.stop();
		}
	}
}
