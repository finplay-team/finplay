package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.entity.ImportStatus;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.MarketDataImport;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.MarketDataImportRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class StockCollectionLockConcurrencyIntegrationTest {

	private static final LocalDateTime DEFENDED_RUN_AT = LocalDateTime.of(2026, 9, 10, 8, 10, 0);
	private static final LocalDateTime REPRODUCTION_RUN_AT = LocalDateTime.of(2026, 9, 17, 8, 10, 0);
	private static final LocalDateTime RETRY_IDEMPOTENCY_RUN_AT = LocalDateTime.of(2026, 9, 24, 8, 10, 0);
	private static final LocalDateTime RETRY_NO_TARGET_RUN_AT = LocalDateTime.of(2026, 10, 1, 8, 10, 0);

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private MarketDataImportRepository marketDataImportRepository;

	@Autowired
	private KisHistoricalCandleImportWriter importWriter;

	@Autowired
	private StockCollectionLock stockCollectionLock;

	@Autowired
	private BusinessDayCalendar businessDayCalendar;

	@Autowired
	private TestClock clock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private Instrument testInstrument;

	private LocalDate testTradingDate;

	private final List<Instrument> retryScenarioInstruments = new ArrayList<>();

	private LocalDate retryScenarioTradingDate;

	private String lockKeyFor(LocalDate tradingDate) {
		return "market:stock-collect:lock:" + tradingDate;
	}

	private Instrument createTempStockInstrument() {
		String symbol = String.format("9%05d", ThreadLocalRandom.current().nextInt(100000));
		return instrumentRepository.saveAndFlush(Instrument.create(
			Market.STOCK, symbol, "락경합종목", BigDecimal.ONE, 10000L, true, LocalDateTime.now()));
	}

	@AfterEach
	void cleanUp() {
		if (testInstrument != null) {
			redisTemplate.delete(lockKeyFor(testTradingDate));
			List<StockCandle> candles = stockCandleRepository
				.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(testInstrument.getId(), testTradingDate);
			if (!candles.isEmpty()) {
				stockCandleRepository.deleteAll(candles);
			}
			List<MarketDataImport> imports = marketDataImportRepository
				.findBySourceTradingDateOrderByCollectedAtDesc(testTradingDate);
			if (!imports.isEmpty()) {
				marketDataImportRepository.deleteAll(imports);
			}
			instrumentRepository.delete(testInstrument);
			testInstrument = null;
		}
		cleanUpRetryScenarioIfNeeded();
	}

	private void cleanUpRetryScenarioIfNeeded() {
		if (retryScenarioTradingDate == null) {
			return;
		}
		redisTemplate.delete(lockKeyFor(retryScenarioTradingDate));
		List<Instrument> allStockInstruments = instrumentRepository
			.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK);
		for (Instrument instrument : allStockInstruments) {
			List<StockCandle> candles = stockCandleRepository.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(
				instrument.getId(), retryScenarioTradingDate);
			if (!candles.isEmpty()) {
				stockCandleRepository.deleteAll(candles);
			}
		}
		List<MarketDataImport> imports = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(retryScenarioTradingDate);
		if (!imports.isEmpty()) {
			marketDataImportRepository.deleteAll(imports);
		}
		for (Instrument instrument : retryScenarioInstruments) {
			instrumentRepository.delete(instrument);
		}
		retryScenarioTradingDate = null;
		retryScenarioInstruments.clear();
	}

	private static RawMinuteCandleDto defaultFallbackCandle() {
		return validCandle(LocalTime.of(9, 0), "10000");
	}

	private static RawMinuteCandleDto validCandle(LocalTime time, String price) {
		BigDecimal p = new BigDecimal(price);
		return new RawMinuteCandleDto(time, p, p, p, p, 100L);
	}

	private KisHistoricalCandleCollector collectorWith(KisHistoricalCandleClient client, StockCollectionLock lock) {
		return new KisHistoricalCandleCollector(
			instrumentRepository, client, stockCandleRepository, importWriter, clock, businessDayCalendar, lock);
	}

	@Test
	@DisplayName("[방어 켠 상태] 실제 Redis 락으로 두 인스턴스가 같은 거래일에 동시에 collect()를 실행해도 "
		+ "market_data_imports는 1건(SUCCESS)만 남고 stock_candles도 1건만 저장된다")
	void collectWithRealLockPersistsExactlyOnceAcrossTwoConcurrentInstances() throws Exception {
		testInstrument = createTempStockInstrument();
		testTradingDate = businessDayCalendar.previousBusinessDay(DEFENDED_RUN_AT.toLocalDate());
		clock.set(DEFENDED_RUN_AT);
		redisTemplate.delete(lockKeyFor(testTradingDate));

		FakeKisHistoricalCandleClient clientA = new FakeKisHistoricalCandleClient();
		clientA.setCandles(testInstrument.getSymbol(), List.of(validCandle(LocalTime.of(9, 0), "50000")));
		FakeKisHistoricalCandleClient clientB = new FakeKisHistoricalCandleClient();
		clientB.setCandles(testInstrument.getSymbol(), List.of(validCandle(LocalTime.of(9, 0), "50000")));

		KisHistoricalCandleCollector collectorA = collectorWith(clientA, stockCollectionLock);
		KisHistoricalCandleCollector collectorB = collectorWith(clientB, stockCollectionLock);

		runConcurrently(collectorA::collect, collectorB::collect);

		List<StockCandle> savedCandles = stockCandleRepository
			.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(testInstrument.getId(), testTradingDate);
		assertThat(savedCandles).hasSize(1);

		List<MarketDataImport> imports = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(testTradingDate);
		assertThat(imports).hasSize(1);
		MarketDataImport onlyImport = imports.get(0);
		assertThat(onlyImport.getStatus()).isNotEqualTo(ImportStatus.FAILED);
		if (onlyImport.getFailureReason() != null) {
			assertThat(onlyImport.getFailureReason()).doesNotContain(testInstrument.getSymbol());
		}
	}

	@Test
	@DisplayName("[방어 비활성/우회 재현] 락이 실제 상호 배제를 하지 않으면(항상 획득 성공) "
		+ "두 인스턴스 동시 실행이 stock_candles 유니크 위반 → FAILED 이력으로 재현된다")
	void collectWithoutRealMutualExclusionReproducesUniqueViolationAsFailedImport() throws Exception {
		testInstrument = createTempStockInstrument();
		testTradingDate = businessDayCalendar.previousBusinessDay(REPRODUCTION_RUN_AT.toLocalDate());
		clock.set(REPRODUCTION_RUN_AT);

		StockCollectionLock alwaysSucceedingLock = mock(StockCollectionLock.class);
		when(alwaysSucceedingLock.tryLock(any()))
			.thenAnswer(invocation -> Optional.of(UUID.randomUUID().toString()));

		CyclicBarrier bothReachedFetch = new CyclicBarrier(2);
		RawMinuteCandleDto candle = validCandle(LocalTime.of(9, 0), "50000");
		KisHistoricalCandleClient barrierGatedClient = (symbol, tradingDate) -> {
			if (!symbol.equals(testInstrument.getSymbol())) {
				return List.of();
			}
			try {
				bothReachedFetch.await(10, TimeUnit.SECONDS);
			} catch (Exception ex) {
				throw new RuntimeException(ex);
			}
			return List.of(candle);
		};

		KisHistoricalCandleCollector collectorA = collectorWith(barrierGatedClient, alwaysSucceedingLock);
		KisHistoricalCandleCollector collectorB = collectorWith(barrierGatedClient, alwaysSucceedingLock);

		runConcurrently(collectorA::collect, collectorB::collect);

		List<StockCandle> savedCandles = stockCandleRepository
			.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(testInstrument.getId(), testTradingDate);
		assertThat(savedCandles).hasSize(1);

		List<MarketDataImport> imports = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(testTradingDate);
		assertThat(imports).hasSize(2);
		assertThat(imports).filteredOn(dataImport -> dataImport.getStatus() == ImportStatus.FAILED).hasSize(1);
		assertThat(imports).filteredOn(dataImport -> dataImport.getStatus() != ImportStatus.FAILED).hasSize(1);

		MarketDataImport failedImport = imports.stream()
			.filter(dataImport -> dataImport.getStatus() == ImportStatus.FAILED)
			.findFirst()
			.orElseThrow();
		assertThat(failedImport.getFailureReason()).contains("예상치 못한 오류");
	}

	@Test
	@DisplayName("[재시도 멱등성] 정규 배치 실행 후 특정 종목만 실패한 상태에서 retryPendingInstruments()를 실행하면 "
		+ "그 종목만 다시 조회되고 이미 수집된 종목은 재조회되지 않는다")
	void retryPendingInstrumentsRefetchesOnlyThePendingInstrumentAndSkipsAlreadyCollectedOnes() throws Exception {
		Instrument instrumentA = createTempStockInstrument();
		Instrument instrumentB = createTempStockInstrument();
		retryScenarioInstruments.add(instrumentA);
		retryScenarioInstruments.add(instrumentB);
		retryScenarioTradingDate = businessDayCalendar.previousBusinessDay(RETRY_IDEMPOTENCY_RUN_AT.toLocalDate());
		clock.set(RETRY_IDEMPOTENCY_RUN_AT);
		redisTemplate.delete(lockKeyFor(retryScenarioTradingDate));

		RawMinuteCandleDto candleA = validCandle(LocalTime.of(9, 0), "50000");
		RawMinuteCandleDto candleB = validCandle(LocalTime.of(9, 0), "60000");

		KisHistoricalCandleClient client = mock(KisHistoricalCandleClient.class);
		when(client.fetchMinuteCandles(anyString(), any())).thenReturn(List.of(defaultFallbackCandle()));
		when(client.fetchMinuteCandles(eq(instrumentA.getSymbol()), any())).thenReturn(List.of(candleA));
		when(client.fetchMinuteCandles(eq(instrumentB.getSymbol()), any()))
			.thenThrow(new RuntimeException("일시적 오류(테스트 주입)"))
			.thenReturn(List.of(candleB));

		KisHistoricalCandleCollector collector = collectorWith(client, stockCollectionLock);

		collector.collect();

		assertThat(stockCandleRepository
			.existsByInstrumentIdAndTradingDate(instrumentA.getId(), retryScenarioTradingDate)).isTrue();
		assertThat(stockCandleRepository
			.existsByInstrumentIdAndTradingDate(instrumentB.getId(), retryScenarioTradingDate)).isFalse();

		List<MarketDataImport> importsAfterRegularRun = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(retryScenarioTradingDate);
		assertThat(importsAfterRegularRun).hasSize(1);
		assertThat(importsAfterRegularRun.get(0).getStatus()).isEqualTo(ImportStatus.PARTIAL_SUCCESS);

		clearInvocations(client);

		collector.retryPendingInstruments();

		verify(client, never()).fetchMinuteCandles(eq(instrumentA.getSymbol()), any());
		verify(client, times(1)).fetchMinuteCandles(eq(instrumentB.getSymbol()), any());

		assertThat(stockCandleRepository
			.existsByInstrumentIdAndTradingDate(instrumentB.getId(), retryScenarioTradingDate)).isTrue();

		List<StockCandle> candlesA = stockCandleRepository
			.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrumentA.getId(), retryScenarioTradingDate);
		assertThat(candlesA).hasSize(1);

		List<MarketDataImport> importsAfterRetry = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(retryScenarioTradingDate);
		assertThat(importsAfterRetry).hasSize(2);
		Long regularRunImportId = importsAfterRegularRun.get(0).getId();
		MarketDataImport retryImport = importsAfterRetry.stream()
			.filter(dataImport -> !dataImport.getId().equals(regularRunImportId))
			.findFirst()
			.orElseThrow();
		assertThat(retryImport.getStatus()).isNotEqualTo(ImportStatus.FAILED);
		if (retryImport.getFailureReason() != null) {
			assertThat(retryImport.getFailureReason()).doesNotContain(instrumentB.getSymbol());
		}
	}

	@Test
	@DisplayName("[재시도 무대상] 재시도 시점에 이미 수집 완료된 종목은 KIS 클라이언트가 재호출하지 않는다")
	void retryPendingInstrumentsDoesNotCallKisClientWhenNothingIsPending() throws Exception {
		Instrument instrumentA = createTempStockInstrument();
		retryScenarioInstruments.add(instrumentA);
		retryScenarioTradingDate = businessDayCalendar.previousBusinessDay(RETRY_NO_TARGET_RUN_AT.toLocalDate());
		clock.set(RETRY_NO_TARGET_RUN_AT);
		redisTemplate.delete(lockKeyFor(retryScenarioTradingDate));

		KisHistoricalCandleClient client = mock(KisHistoricalCandleClient.class);
		when(client.fetchMinuteCandles(anyString(), any())).thenReturn(List.of(defaultFallbackCandle()));

		KisHistoricalCandleCollector collector = collectorWith(client, stockCollectionLock);

		collector.collect();
		assertThat(stockCandleRepository
			.existsByInstrumentIdAndTradingDate(instrumentA.getId(), retryScenarioTradingDate)).isTrue();

		List<MarketDataImport> importsAfterRegularRun = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(retryScenarioTradingDate);
		assertThat(importsAfterRegularRun).hasSize(1);
		assertThat(importsAfterRegularRun.get(0).getStatus()).isNotEqualTo(ImportStatus.FAILED);

		clearInvocations(client);

		collector.retryPendingInstruments();

		verify(client, never()).fetchMinuteCandles(eq(instrumentA.getSymbol()), any());

		List<MarketDataImport> importsAfterRetry = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(retryScenarioTradingDate);
		assertThat(importsAfterRetry).hasSize(2);
		assertThat(importsAfterRetry).noneMatch(dataImport -> dataImport.getStatus() == ImportStatus.FAILED);
	}

	private void runConcurrently(ThrowingRunnable actionA, ThrowingRunnable actionB) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Void> futureA = executor.submit(toCallable(actionA, ready, start));
			Future<Void> futureB = executor.submit(toCallable(actionB, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			futureA.get(15, TimeUnit.SECONDS);
			futureB.get(15, TimeUnit.SECONDS);
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}

	private Callable<Void> toCallable(ThrowingRunnable action, CountDownLatch ready, CountDownLatch start) {
		return () -> {
			ready.countDown();
			start.await();
			action.run();
			return null;
		};
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
