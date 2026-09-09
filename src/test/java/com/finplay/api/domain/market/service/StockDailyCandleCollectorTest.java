package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.market.entity.ImportStatus;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.MarketDataImport;
import com.finplay.api.domain.market.entity.StockDailyCandle;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.MarketDataImportRepository;
import com.finplay.api.domain.market.repository.StockDailyCandleRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class StockDailyCandleCollectorTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime WEEKDAY_RUN_AT = LocalDateTime.of(2026, 7, 30, 8, 25, 0);
	private static final LocalDate TARGET_END_DATE = LocalDate.of(2026, 7, 29);
	private static final String SYMBOL = "005930";

	private final InstrumentRepository instrumentRepository = mock(InstrumentRepository.class);
	private final StockDailyCandleRepository stockDailyCandleRepository = mock(StockDailyCandleRepository.class);
	private final MarketDataImportRepository marketDataImportRepository = mock(MarketDataImportRepository.class);
	private final StockDailyCandleImportWriter importWriter = new StockDailyCandleImportWriter(
		stockDailyCandleRepository, marketDataImportRepository);

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

	private static StockDailyCandle storedCandle(Instrument instrument, LocalDate tradingDate) {
		return StockDailyCandle.create(
			instrument, tradingDate, new BigDecimal("70000"), new BigDecimal("70500"),
			new BigDecimal("69500"), new BigDecimal("70200"), 100L, "KIS_DAILY", LocalDateTime.now());
	}

	private static RawDailyCandleDto validRawCandle(LocalDate tradingDate) {
		BigDecimal price = new BigDecimal("70000");
		return new RawDailyCandleDto(tradingDate, price, price, price, price, 100L);
	}

	private StockDailyCandleCollector newCollector(
		InstrumentRepository instrumentRepository, KisDailyCandleClient client) {
		return new StockDailyCandleCollector(
			instrumentRepository, client, stockDailyCandleRepository, importWriter,
			fixedClock(WEEKDAY_RUN_AT), new BusinessDayCalendar(), stockCollectionLock);
	}

	@Test
	void collectRequestsFullThreeYearRangeWhenInstrumentHasNoStoredDailyCandleYet() {
		Instrument instrument = stockInstrument(1L, SYMBOL);
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(instrument));
		when(stockDailyCandleRepository.findFirstByInstrumentIdOrderByTradingDateDesc(1L))
			.thenReturn(Optional.empty());

		KisDailyCandleClient client = mock(KisDailyCandleClient.class);
		LocalDate expectedRangeStart = TARGET_END_DATE.minusYears(3);
		when(client.fetchDailyCandles(eq(SYMBOL), eq(expectedRangeStart), eq(TARGET_END_DATE)))
			.thenReturn(List.of(validRawCandle(TARGET_END_DATE)));

		StockDailyCandleCollector collector = newCollector(instrumentRepository, client);

		collector.collect();

		verify(client).fetchDailyCandles(SYMBOL, expectedRangeStart, TARGET_END_DATE);
		ArgumentCaptor<List<StockDailyCandle>> savedCaptor = ArgumentCaptor.forClass(List.class);
		verify(stockDailyCandleRepository).saveAll(savedCaptor.capture());
		assertThat(savedCaptor.getValue()).hasSize(1);
		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository).save(importCaptor.capture());
		assertThat(importCaptor.getValue().getStatus()).isEqualTo(ImportStatus.SUCCESS);
	}

	@Test
	void collectRequestsOnlyFromDayAfterLatestStoredTradingDateWhenAlreadyMostlyUpToDate() {
		Instrument instrument = stockInstrument(1L, SYMBOL);
		LocalDate latestStored = TARGET_END_DATE.minusDays(1);
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(instrument));
		when(stockDailyCandleRepository.findFirstByInstrumentIdOrderByTradingDateDesc(1L))
			.thenReturn(Optional.of(storedCandle(instrument, latestStored)));

		KisDailyCandleClient client = mock(KisDailyCandleClient.class);
		when(client.fetchDailyCandles(eq(SYMBOL), eq(TARGET_END_DATE), eq(TARGET_END_DATE)))
			.thenReturn(List.of(validRawCandle(TARGET_END_DATE)));

		StockDailyCandleCollector collector = newCollector(instrumentRepository, client);

		collector.collect();

		verify(client).fetchDailyCandles(SYMBOL, TARGET_END_DATE, TARGET_END_DATE);
		verify(client, never()).fetchDailyCandles(eq(SYMBOL), eq(TARGET_END_DATE.minusYears(3)), any());
	}

	@Test
	void collectSkipsKisCallWhenLatestStoredTradingDateAlreadyEqualsTargetEndDate() {
		Instrument instrument = stockInstrument(1L, SYMBOL);
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(instrument));
		when(stockDailyCandleRepository.findFirstByInstrumentIdOrderByTradingDateDesc(1L))
			.thenReturn(Optional.of(storedCandle(instrument, TARGET_END_DATE)));

		KisDailyCandleClient neverCalledClient = mock(KisDailyCandleClient.class);

		StockDailyCandleCollector collector = newCollector(instrumentRepository, neverCalledClient);

		collector.collect();

		verify(neverCalledClient, never()).fetchDailyCandles(any(), any(), any());
		verify(stockDailyCandleRepository, never()).saveAll(any());
		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository).save(importCaptor.capture());
		assertThat(importCaptor.getValue().getStatus()).isEqualTo(ImportStatus.SUCCESS);
		assertThat(importCaptor.getValue().getFailureReason()).isNull();
	}

	@Test
	void collectRequestsOnlyRemainingPartialRangeWhenInstrumentHasRecentPartialHistory() {
		Instrument instrument = stockInstrument(1L, SYMBOL);
		LocalDate latestStored = TARGET_END_DATE.minusDays(30);
		LocalDate expectedRangeStart = TARGET_END_DATE.minusDays(29);
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(instrument));
		when(stockDailyCandleRepository.findFirstByInstrumentIdOrderByTradingDateDesc(1L))
			.thenReturn(Optional.of(storedCandle(instrument, latestStored)));

		KisDailyCandleClient client = mock(KisDailyCandleClient.class);
		when(client.fetchDailyCandles(eq(SYMBOL), eq(expectedRangeStart), eq(TARGET_END_DATE)))
			.thenReturn(List.of(validRawCandle(TARGET_END_DATE)));

		StockDailyCandleCollector collector = newCollector(instrumentRepository, client);

		collector.collect();

		verify(client).fetchDailyCandles(SYMBOL, expectedRangeStart, TARGET_END_DATE);
		verify(client, never()).fetchDailyCandles(eq(SYMBOL), eq(TARGET_END_DATE.minusYears(3)), any());
	}

	@Test
	void collectSkipsOnlyInstrumentWhoseKisCallThrowsAndStillSavesTheOtherInstrument() {
		Instrument healthyInstrument = stockInstrument(1L, "005930");
		Instrument flakyInstrument = stockInstrument(2L, "000660");
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK))
			.thenReturn(List.of(healthyInstrument, flakyInstrument));
		when(stockDailyCandleRepository.findFirstByInstrumentIdOrderByTradingDateDesc(anyLong()))
			.thenReturn(Optional.empty());

		KisDailyCandleClient partiallyFlakyClient = (symbol, from, to) -> {
			if ("000660".equals(symbol)) {
				throw new RuntimeException("연결이 재설정되었습니다");
			}
			return List.of(validRawCandle(TARGET_END_DATE));
		};

		StockDailyCandleCollector collector = newCollector(instrumentRepository, partiallyFlakyClient);

		collector.collect();

		ArgumentCaptor<List<StockDailyCandle>> savedCaptor = ArgumentCaptor.forClass(List.class);
		verify(stockDailyCandleRepository, times(1)).saveAll(savedCaptor.capture());
		assertThat(savedCaptor.getValue()).hasSize(1);
		assertThat(savedCaptor.getValue().get(0).getInstrument().getSymbol()).isEqualTo("005930");

		ArgumentCaptor<MarketDataImport> importCaptor = ArgumentCaptor.forClass(MarketDataImport.class);
		verify(marketDataImportRepository).save(importCaptor.capture());
		MarketDataImport savedImport = importCaptor.getValue();
		assertThat(savedImport.getStatus()).isEqualTo(ImportStatus.PARTIAL_SUCCESS);
		assertThat(savedImport.getFailureReason()).contains("000660");
	}

	@Test
	void collectSkipsEntirelyWithoutCallingKisOrRecordingImportWhenLockIsNotAcquired() {
		when(stockCollectionLock.tryLock(TARGET_END_DATE)).thenReturn(Optional.empty());
		InstrumentRepository neverCalledInstrumentRepository = mock(InstrumentRepository.class);
		KisDailyCandleClient neverCalledClient = mock(KisDailyCandleClient.class);

		StockDailyCandleCollector collector = newCollector(neverCalledInstrumentRepository, neverCalledClient);

		collector.collect();

		verify(neverCalledInstrumentRepository, never()).findByMarketAndTutorialSampleFalseOrderByIdAsc(any());
		verify(neverCalledClient, never()).fetchDailyCandles(any(), any(), any());
		verify(marketDataImportRepository, never()).save(any());
		verify(stockCollectionLock, never()).unlock(any(), any());
	}

	@Test
	void collectReleasesLockWithTheAcquiredTokenAfterASuccessfulRun() {
		when(stockCollectionLock.tryLock(TARGET_END_DATE)).thenReturn(Optional.of("held-token"));
		when(instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK)).thenReturn(List.of());

		StockDailyCandleCollector collector = newCollector(instrumentRepository, mock(KisDailyCandleClient.class));

		collector.collect();

		verify(stockCollectionLock).unlock(TARGET_END_DATE, "held-token");
	}
}
