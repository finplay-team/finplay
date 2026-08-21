// StockCollectionLock(COLLECT-STAB-001)이 같은 거래일에 대한 두 KisHistoricalCandleCollector 동시 실행에서 실제로 stock_candles 유니크 위반을 막는지, 방어가 없으면 그 위반이 실제로 재현되는지를 한 클래스에서 대조한다 (tasks.md 항목 2).
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
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.market.entity.ImportStatus;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.MarketDataImport;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.MarketDataImportRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
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
	// 2026-09-24(목) 08:10 KST 실행 — 직전 영업일은 2026-09-24가 아니라 2026-09-23(수, holidays-2026.txt 확인 결과
	// 공휴일 아님). [재시도 멱등성] 테스트 전용 — 위 두 테스트와 다른 거래일을 써서 market_data_imports 잔여물이
	// 서로 섞이지 않게 한다.
	private static final LocalDateTime RETRY_IDEMPOTENCY_RUN_AT = LocalDateTime.of(2026, 9, 24, 8, 10, 0);
	// 2026-10-01(목) 08:10 KST 실행 — 직전 영업일은 2026-09-30(수, holidays-2026.txt 확인 결과 공휴일 아님).
	// [재시도 무대상] 테스트 전용.
	private static final LocalDateTime RETRY_NO_TARGET_RUN_AT = LocalDateTime.of(2026, 10, 1, 8, 10, 0);

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

	// [재시도 멱등성]·[재시도 무대상] 두 시나리오 전용 — 위 단일 testInstrument와 달리 종목을 최대 2개 쓴다.
	// 두 시나리오 모두 "그 거래일의 STOCK 종목 전체가 이미 수집 완료"를 실제 16종까지 포함해 재현해야 해서
	// (그래야 [재시도 무대상]의 "KIS 클라이언트 0회 호출"이 우연이 아니라 결정론적으로 성립한다), cleanUp도
	// 임시 종목뿐 아니라 그 거래일의 STOCK 종목 전체 분봉을 함께 지운다(아래 cleanUpRetryScenarioIfNeeded).
	private final List<Instrument> retryScenarioInstruments = new ArrayList<>();

	private LocalDate retryScenarioTradingDate;

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

	// [재시도 멱등성]·[재시도 무대상] 전용 정리. 두 테스트 모두 실제 STOCK 종목 전체를 기본 분봉으로 채워
	// "그 거래일이 전부 수집 완료"인 상태를 만들기 때문에, 임시 종목만 지우는 위 로직으로는 부족하다 — 그
	// 거래일의 STOCK 종목 전체(findByMarketAndTutorialSampleFalseOrderByIdAsc, collector 자신이 순회하는 것과
	// 같은 조회)를 대상으로 분봉을 지운다.
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
		// [재시도 멱등성]·[재시도 무대상] 전용 — 이 테스트가 만들지 않은 실제 16종을 포함해 그 거래일의 모든
		// STOCK 종목을 기본으로 성공 처리하기 위한 값. 가격 자체는 검증 규칙(고가>=시가/종가/저가 등)만
		// 만족하면 되므로 임의값이다.
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
		// collect()는 testInstrument뿐 아니라 그 시점의 STOCK 종목 전체를 순회한다. 같은 Testcontainers MySQL을 공유하는
		// 다른 도메인의 @SpringBootTest들(order/journal/portfolio 등)이 6자리 숫자가 아닌 심볼로 STOCK 종목을 만들고
		// 정리하지 않는 경우가 있어(MarketDataPipelineIntegrationTest의 같은 주석 — collectThenScheduleThenReplay...
		// 참고), 그런 잔여 종목이 있으면 validateInstrumentCandles의 종목코드 형식 검사에서 항상 실패해 이번 실행 전체가
		// PARTIAL_SUCCESS로 남을 수 있다 — 이 테스트가 검증하는 락 배타성과는 무관한 노이즈이므로 imports.get(0)의 상태를
		// SUCCESS로 단정하지 않고, testInstrument 자신은 실패 목록에 없다는 것으로 좁혀 확인한다.
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
		// "이긴 쪽"의 상태는 SUCCESS로 단정하지 않는다 — 위 [방어 켠 상태]와 같은 이유로, 공유 DB에 다른 도메인
		// 테스트가 남긴 6자리 숫자가 아닌 STOCK 종목이 있으면 그 종목의 종목코드 형식 검사 실패가 섞여 이긴 쪽도
		// PARTIAL_SUCCESS로 남을 수 있다. 이 테스트가 검증하는 것은 "정확히 한 쪽만 유니크 위반으로 FAILED를 남긴다"이므로
		// FAILED 개수만 정확히 하나로 좁혀 확인한다.
		assertThat(imports).filteredOn(dataImport -> dataImport.getStatus() == ImportStatus.FAILED).hasSize(1);
		assertThat(imports).filteredOn(dataImport -> dataImport.getStatus() != ImportStatus.FAILED).hasSize(1);

		MarketDataImport failedImport = imports.stream()
			.filter(dataImport -> dataImport.getStatus() == ImportStatus.FAILED)
			.findFirst()
			.orElseThrow();
		// collect()의 최상위 catch가 남기는 문구("수집이 예상치 못한 오류로 중단되었습니다: ...")로, 이 FAILED가
		// 종목별 검증 실패가 아니라 저장 단계의 예외(유니크 위반)에서 왔다는 것을 고정한다.
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
		// 이 테스트가 만들지 않은 실제 16종을 포함한 나머지 모든 종목은 기본 분봉으로 항상 성공 처리한다 —
		// 아래 A·B 전용 stub이 이 뒤에 등록되어 그 두 종목에 대해서는 이 기본값을 덮어쓴다(Mockito는 매칭되는
		// stub 중 나중에 등록된 것을 우선한다).
		when(client.fetchMinuteCandles(anyString(), any())).thenReturn(List.of(defaultFallbackCandle()));
		when(client.fetchMinuteCandles(eq(instrumentA.getSymbol()), any())).thenReturn(List.of(candleA));
		// B는 정규 배치에서 한 번은 실패(KIS 일시적 오류 상황을 흉내)하고, 재시도에서는 성공한다 — Mockito
		// 연속 stubbing(첫 호출 예외, 이후 호출 정상 반환).
		when(client.fetchMinuteCandles(eq(instrumentB.getSymbol()), any()))
			.thenThrow(new RuntimeException("일시적 오류(테스트 주입)"))
			.thenReturn(List.of(candleB));

		KisHistoricalCandleCollector collector = collectorWith(client, stockCollectionLock);

		// 정규 배치 — A·실제 16종은 성공하고, B는 종목 단위 실패로 흡수되어 이번 실행은 PARTIAL_SUCCESS로 남는다.
		collector.collect();

		assertThat(stockCandleRepository
			.existsByInstrumentIdAndTradingDate(instrumentA.getId(), retryScenarioTradingDate)).isTrue();
		assertThat(stockCandleRepository
			.existsByInstrumentIdAndTradingDate(instrumentB.getId(), retryScenarioTradingDate)).isFalse();

		List<MarketDataImport> importsAfterRegularRun = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(retryScenarioTradingDate);
		assertThat(importsAfterRegularRun).hasSize(1);
		assertThat(importsAfterRegularRun.get(0).getStatus()).isEqualTo(ImportStatus.PARTIAL_SUCCESS);

		// 정규 배치에서의 호출은 이 시점부터의 검증과 무관하므로 지운다 — 이후 verify는 재시도 실행만 본다.
		clearInvocations(client);

		// 재시도 — 이미 수집된 A와 실제 16종은 재조회되지 않고, 아직 없는 B만 다시 조회되어 이번엔 성공한다.
		collector.retryPendingInstruments();

		verify(client, never()).fetchMinuteCandles(eq(instrumentA.getSymbol()), any());
		verify(client, times(1)).fetchMinuteCandles(eq(instrumentB.getSymbol()), any());

		assertThat(stockCandleRepository
			.existsByInstrumentIdAndTradingDate(instrumentB.getId(), retryScenarioTradingDate)).isTrue();

		// A는 재조회되지 않았으므로 정규 배치 때 저장된 1건 그대로이지 중복 저장되지 않는다.
		List<StockCandle> candlesA = stockCandleRepository
			.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrumentA.getId(), retryScenarioTradingDate);
		assertThat(candlesA).hasSize(1);

		List<MarketDataImport> importsAfterRetry = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(retryScenarioTradingDate);
		assertThat(importsAfterRetry).hasSize(2);
		// 재시도 실행의 상태를 SUCCESS로 단정하지 않는다 — 위 두 시나리오와 같은 이유로, 공유 DB에 남은(다른 도메인
		// 테스트가 정리하지 않은) 6자리 숫자가 아닌 STOCK 종목은 그 어떤 거래일에도 영원히 "수집 완료"가 될 수 없어
		// (validateInstrumentCandles의 종목코드 형식 검사가 항상 실패) 매 재시도마다 다시 시도되고 다시 실패한다 —
		// 이 테스트가 검증하는 B의 재시도 성공과는 무관한 노이즈다. collectedAt이 두 실행에서 동일해 순서로 구분할 수
		// 없으므로, 정규 배치 이력의 id로 재시도 이력을 골라내 그 상태가 FAILED가 아니고 실패 사유에 B가 없다는 것으로
		// 좁혀 확인한다.
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
		// 이 테스트 종목과 실제 16종 모두 기본 분봉으로 성공 처리해, 정규 배치 한 번으로 그 거래일 전체를
		// "이미 수집 완료" 상태로 만든다 — 그래야 재시도가 KIS를 전혀 호출하지 않는 것이 우연이 아니라
		// 결정론적으로 성립한다.
		when(client.fetchMinuteCandles(anyString(), any())).thenReturn(List.of(defaultFallbackCandle()));

		KisHistoricalCandleCollector collector = collectorWith(client, stockCollectionLock);

		collector.collect();
		assertThat(stockCandleRepository
			.existsByInstrumentIdAndTradingDate(instrumentA.getId(), retryScenarioTradingDate)).isTrue();

		List<MarketDataImport> importsAfterRegularRun = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(retryScenarioTradingDate);
		assertThat(importsAfterRegularRun).hasSize(1);
		// SUCCESS로 단정하지 않는다 — 위 시나리오들과 같은 이유(공유 DB에 다른 도메인 테스트가 남긴 6자리 숫자가 아닌
		// STOCK 종목이 있으면 항상 종목코드 형식 검사에서 실패해 PARTIAL_SUCCESS가 섞일 수 있다).
		assertThat(importsAfterRegularRun.get(0).getStatus()).isNotEqualTo(ImportStatus.FAILED);

		clearInvocations(client);

		collector.retryPendingInstruments();

		// verifyNoInteractions(client)는 쓰지 않는다 — 공유 DB에 6자리 숫자가 아닌 심볼의 STOCK 종목이 남아 있으면
		// (다른 도메인 테스트가 정리하지 않은 것) validateInstrumentCandles의 종목코드 형식 검사가 그 종목에 대해서는
		// 영원히 실패해 "그날 전체가 이미 수집 완료" 상태가 될 수 없고, 매 재시도마다 그 종목만 다시 조회된다 — 이
		// 테스트가 실제로 보장해야 하는 것은 "이미 수집된 A는 재조회되지 않는다"이므로 A에 한정해 확인한다.
		verify(client, never()).fetchMinuteCandles(eq(instrumentA.getSymbol()), any());

		List<MarketDataImport> importsAfterRetry = marketDataImportRepository
			.findBySourceTradingDateOrderByCollectedAtDesc(retryScenarioTradingDate);
		assertThat(importsAfterRetry).hasSize(2);
		assertThat(importsAfterRetry).noneMatch(dataImport -> dataImport.getStatus() == ImportStatus.FAILED);
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
