// StockCollectionLock(COLLECT-STAB-001)이 같은 거래일에 대한 두 KisHistoricalCandleCollector 동시 실행에서 실제로 stock_candles 유니크 위반을 막는지, 방어가 없으면 그 위반이 실제로 재현되는지를 한 클래스에서 대조한다 (tasks.md 항목 2).
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.market.domain.ImportStatus;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.MarketDataImport;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.MarketDataImportRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

// [방어 켠 상태]가 먼저 "락이 실제로 KIS 호출·저장 하나만 통과시킨다"를 증명하고, [재현 대조]가 그 다음 같은
// 클래스에서 "락을 우회하면(항상 성공하는 mock) 실제로 stock_candles 유니크 위반 경로를 타서 FAILED가 남는다"를
// 대조한다 — CryptoWatchLockConcurrencyIntegrationTest(ADR-0014)와 같은 구성이다.
//
// 두 시나리오 모두 새로 만든 전용 STOCK 종목 하나만 대상으로 삼는다. KisHistoricalCandleCollector는
// findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK)로 그 시점의 STOCK 종목 전체(마이그레이션이 시드한
// 실제 16종 포함)를 순회하지만, FakeKisHistoricalCandleClient/커스텀 client는 이 테스트 종목의 심볼에만 분봉을
// 채워 두고 나머지 심볼은 기본값(빈 리스트)을 반환한다 — 빈 리스트는 오류가 아니므로(validateInstrumentCandles)
// 실제 종목들은 항상 성공 처리되어 경합에 끼어들지 않는다. 새 종목은 uk_instruments_symbol이 전역 유니크라
// 무작위 6자리 심볼로 만들고 @AfterEach에서 stock_candles → market_data_imports → instruments 순으로 정리한다
// (MarketDataPipelineIntegrationTest가 실제 종목을 새로 만들지 않는 이유와 같다 — InstrumentRepositoryTest의
// 전역 카운트 단정을 건드리지 않으려면 만든 것은 반드시 스스로 지워야 한다).
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class StockCollectionLockConcurrencyIntegrationTest {

	// 2026-09-10(목) 08:10 KST 실행 — 직전 영업일은 주말·공휴일 없이 2026-09-09(수).
	private static final LocalDateTime DEFENDED_RUN_AT = LocalDateTime.of(2026, 9, 10, 8, 10, 0);
	// 2026-09-17(목) 08:10 KST 실행 — 직전 영업일은 2026-09-16(수). [방어 켠] 테스트와 다른 거래일을 써서 두
	// 테스트가 서로의 market_data_imports 잔여물에 영향받지 않게 한다.
	private static final LocalDateTime REPRODUCTION_RUN_AT = LocalDateTime.of(2026, 9, 17, 8, 10, 0);

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private MarketDataImportRepository marketDataImportRepository;

	@Autowired
	private KisHistoricalCandleImportWriter importWriter;

	// [방어 켠 상태] 테스트 전용 — 실제 Redis 배선의 락을 그대로 재사용한다.
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

	// StockCollectionLock의 키 접두사(market:stock-collect:lock:)를 그대로 복제한다 — 공유 Redis는 Spring Context보다
	// 오래 살아, 이전 실행이 비정상 종료해 TTL이 남아 있으면 이번 실행의 tryLock이 처음부터 실패해 [방어 켠] 테스트가
	// "둘 다 실패"로 깨질 수 있다(CryptoWatchLockConcurrencyIntegrationTest의 같은 이유·같은 방어).
	private String lockKeyFor(LocalDate tradingDate) {
		return "market:stock-collect:lock:" + tradingDate;
	}

	private Instrument createTempStockInstrument() {
		// uk_instruments_symbol이 전역 유니크라 실제 16종(005930 등)과 절대 겹치지 않는 9xxxxx 대역에서 무작위로 고른다.
		String symbol = String.format("9%05d", ThreadLocalRandom.current().nextInt(100000));
		return instrumentRepository.saveAndFlush(Instrument.create(
			Market.STOCK, symbol, "락경합종목", BigDecimal.ONE, 10000L, true, LocalDateTime.now()));
	}

	@AfterEach
	void cleanUp() {
		if (testInstrument == null) {
			return;
		}
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
		// 공유 Redis는 Spring Context보다 오래 산다 — 이 테스트가 쓸 키를 먼저 비워 이전 실행의 잔여 TTL에
		// 좌우되지 않게 한다(두 스레드 다 tryLock에 실패해 이 테스트가 거짓으로 깨지는 것을 막는다).
		redisTemplate.delete(lockKeyFor(testTradingDate));

		FakeKisHistoricalCandleClient clientA = new FakeKisHistoricalCandleClient();
		clientA.setCandles(testInstrument.getSymbol(), List.of(validCandle(LocalTime.of(9, 0), "50000")));
		FakeKisHistoricalCandleClient clientB = new FakeKisHistoricalCandleClient();
		clientB.setCandles(testInstrument.getSymbol(), List.of(validCandle(LocalTime.of(9, 0), "50000")));

		// 서로 다른 KisHistoricalCandleCollector 인스턴스 두 개 — blue·green 두 인스턴스가 같은 락(stockCollectionLock)만
		// 공유하고 그 외 협력자는 각자 새로 조립하는 상황을 흉내낸다.
		KisHistoricalCandleCollector collectorA = collectorWith(clientA, stockCollectionLock);
		KisHistoricalCandleCollector collectorB = collectorWith(clientB, stockCollectionLock);

		runConcurrently(collectorA::collect, collectorB::collect);

		List<StockCandle> savedCandles = stockCandleRepository
			.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(testInstrument.getId(), testTradingDate);
		assertThat(savedCandles).hasSize(1);

		List<MarketDataImport> imports = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(testTradingDate);
		assertThat(imports).hasSize(1);
		assertThat(imports.get(0).getStatus()).isEqualTo(ImportStatus.SUCCESS);
	}

	@Test
	@DisplayName("[방어 비활성/우회 재현] 락이 실제 상호 배제를 하지 않으면(항상 획득 성공) "
		+ "두 인스턴스 동시 실행이 stock_candles 유니크 위반 → FAILED 이력으로 재현된다")
	void collectWithoutRealMutualExclusionReproducesUniqueViolationAsFailedImport() throws Exception {
		testInstrument = createTempStockInstrument();
		testTradingDate = businessDayCalendar.previousBusinessDay(REPRODUCTION_RUN_AT.toLocalDate());
		clock.set(REPRODUCTION_RUN_AT);

		// tryLock을 호출할 때마다 매번 서로 다른 토큰으로 성공시킨다 — 실제 경합 조정을 전혀 하지 않으면서도
		// 호출부 입장에서는 "이번에도 내가 락을 얻었다"로 보이는, 락 도입 전의 무방비 상태를 그대로 재현한다
		// (CryptoWatchLockConcurrencyIntegrationTest의 alwaysSucceedingLockWithFreshTokens와 같은 방식).
		StockCollectionLock alwaysSucceedingLock = mock(StockCollectionLock.class);
		when(alwaysSucceedingLock.tryLock(any()))
			.thenAnswer(invocation -> Optional.of(UUID.randomUUID().toString()));

		// existsByInstrumentIdAndTradingDate 확인은 두 스레드 모두 아직 아무 것도 저장되지 않은 시점에 통과하지만,
		// 그 뒤 언제 실제로 DB에 저장을 시도하느냐는 스레드 스케줄링에 달려 있어 타이밍만으로는 한쪽이 다른 쪽보다
		// 훨씬 빨리 끝나 우연히 충돌을 피할 수 있다(CryptoWatchLockConcurrencyIntegrationTest의 같은 함정). fetch
		// 시점에 CyclicBarrier(2)로 두 스레드를 다시 맞춰, 어느 쪽도 저장을 시작하기 전에 반드시 인터리빙된 상태로
		// 진입하게 만든다 — 그래야 유니크 위반 재현이 우연이 아니라 결정론적이다.
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

		// stock_candles의 UNIQUE(instrument_id, trading_date, candle_time) 제약 자체는 지켜져 중복 행은 저장되지
		// 않는다 — 다만 그 대가로 진 쪽의 collect() 전체가 importWriter.persist()의 트랜잭션 실패로 롤백되고,
		// collect()의 최상위 catch가 이를 "좁힐 수 없는 전체 오류"로 오인해 FAILED 이력을 남긴다. 애초에 그 실행
		// 자체가 일어나지 않아야 했던 것이 실패 이력으로 남는 것 — 이것이 COLLECT-STAB-001이 막으려는 문제다.
		List<StockCandle> savedCandles = stockCandleRepository
			.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(testInstrument.getId(), testTradingDate);
		assertThat(savedCandles).hasSize(1);

		List<MarketDataImport> imports = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(testTradingDate);
		assertThat(imports).hasSize(2);
		assertThat(imports).extracting(MarketDataImport::getStatus)
			.containsExactlyInAnyOrder(ImportStatus.SUCCESS, ImportStatus.FAILED);

		MarketDataImport failedImport = imports.stream()
			.filter(dataImport -> dataImport.getStatus() == ImportStatus.FAILED)
			.findFirst()
			.orElseThrow();
		// collect()의 최상위 catch가 남기는 문구("수집이 예상치 못한 오류로 중단되었습니다: ...")로, 이 FAILED가
		// 종목별 검증 실패가 아니라 저장 단계의 예외(유니크 위반)에서 왔다는 것을 고정한다.
		assertThat(failedImport.getFailureReason()).contains("예상치 못한 오류");
	}

	// CryptoWatchLockConcurrencyIntegrationTest의 ready/start CountDownLatch 관례를 그대로 재사용한다 — 두 액션을
	// 준비 완료 후 동시에 출발시켜 실제 경합을 재현한다.
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
