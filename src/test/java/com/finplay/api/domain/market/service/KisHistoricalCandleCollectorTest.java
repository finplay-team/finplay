package com.finplay.api.domain.market.service;

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
import com.finplay.api.domain.market.entity.ImportStatus;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.MarketDataImport;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.MarketDataImportRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
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
	private static final LocalDateTime WEEKDAY_RUN_AT = LocalDateTime.of(2026, 7, 30, 8, 10, 0);
	private static final LocalDate EXPECTED_TRADING_DATE = LocalDate.of(2026, 7, 29);

	private final InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
	private final StockCandleRepository stockCandleRepository = mock(StockCandleRepository.class);
	private final MarketDataImportRepository marketDataImportRepository = mock(MarketDataImportRepository.class);
	private final KisHistoricalCandleImportWriter importWriter = new KisHistoricalCandleImportWriter(
		stockCandleRepository, marketDataImportRepository);

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

		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(1L, EXPECTED_TRADING_DATE))
			.thenReturn(false);
		collector.collect();

		when(stockCandleRepository.existsByInstrumentIdAndTradingDate(1L, EXPECTED_TRADING_DATE))
			.thenReturn(true);
		collector.collect();

		verify(spyClient, times(1)).fetchMinuteCandles(eq("005930"), eq(EXPECTED_TRADING_DATE));
		verify(stockCandleRepository, times(1)).saveAll(any());
		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository, times(2)).save(importCaptor.capture());
		assertThat(importCaptor.getAllValues())
			.extracting(MarketDataImport::getStatus)
			.containsExactly(ImportStatus.SUCCESS, ImportStatus.SUCCESS);
	}

	@Test
	void collectNeverDependsOnStockReplaySessionRepository() {
		Field[] fields = KisHistoricalCandleCollector.class.getDeclaredFields();
		boolean referencesStockReplaySessionRepository = Arrays.stream(fields)
			.anyMatch(field -> field.getType().getSimpleName().equals("StockReplaySessionRepository"));

		assertThat(referencesStockReplaySessionRepository).isFalse();
	}

	@Test
	void collectResolvesPreviousBusinessDaySkippingWeekendAt0810KstClock() {
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
