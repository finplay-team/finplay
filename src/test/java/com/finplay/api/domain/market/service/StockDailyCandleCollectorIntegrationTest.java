// Fake KIS 응답으로 일봉 아카이브 배치(최초 전량→증분→재실행, 종목 단위 실패 격리)를 실제 MySQL·Redis로 검증하는 통합 테스트다.
package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.domain.market.entity.ImportStatus;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.MarketDataImport;
import com.finplay.api.domain.market.entity.StockDailyCandle;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.MarketDataImportRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockDailyCandleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class StockDailyCandleCollectorIntegrationTest {

	// 이 테스트는 새 종목을 만들지 않고 마이그레이션이 시드한 실제 주식 16종을 그대로 재사용한다
	// (MarketDataPipelineIntegrationTest와 같은 이유 — collector가 STOCK 종목 전체를 순회하며 6자리 숫자
	// 심볼인지부터 검사하므로, 새 종목을 만들면 InstrumentRepositoryTest의 전역 카운트 검증이 깨진다).
	private List<Instrument> realStockInstruments() {
		return instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK);
	}

	private Instrument realStockInstrument(int index) {
		return realStockInstruments().get(index);
	}

	// 시나리오 A(최초 전량 적재→증분 1회→재실행)의 거래일 3개 — 2026년 7월은 holidays-2026.txt에 공휴일이 없어
	// 평일 산정이 단순하다. 과거 두 날짜(2024·2025)는 "3년 전량"을 상징적으로 나타내는 값일 뿐, 실제 3년치 750건을
	// 채우지는 않는다(그 페이징 자체는 KisDailyCandleClientImplTest가 이미 검증했다 — 이 테스트는 Collector의 빈
	// 구간 계산·저장·멱등성만 본다).
	private static final LocalDate ARCHIVE_ROW_2024 = LocalDate.of(2024, 7, 15);
	private static final LocalDate ARCHIVE_ROW_2025 = LocalDate.of(2025, 7, 15);
	private static final LocalDate TARGET_END_DATE_1 = LocalDate.of(2026, 7, 13); // 월, 최초 실행의 직전 영업일
	private static final LocalDate SERVICE_DATE_1 = LocalDate.of(2026, 7, 14); // 화
	private static final LocalDate TARGET_END_DATE_2 = LocalDate.of(2026, 7, 14); // 화, 증분 실행의 직전 영업일
	private static final LocalDate SERVICE_DATE_2 = LocalDate.of(2026, 7, 15); // 수(재실행도 같은 시각 재사용)

	// 시나리오 B(종목 단위 실패 격리 + stock_candles 회귀) 전용 거래일 — 시나리오 A와 겹치지 않는다.
	private static final LocalDate TARGET_END_DATE_3 = LocalDate.of(2026, 7, 15); // 수
	private static final LocalDate SERVICE_DATE_3 = LocalDate.of(2026, 7, 16); // 목

	@Autowired
	private TestClock clock;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockDailyCandleRepository stockDailyCandleRepository;

	@Autowired
	private MarketDataImportRepository marketDataImportRepository;

	// STOCK-DAILY-005 회귀 확인 전용 — 이 배치가 1분봉 테이블을 하나도 건드리지 않는지 확인한다.
	@Autowired
	private StockCandleRepository stockCandleRepository;

	// 실제 Spring 빈 그대로 재사용한다 — 저장 트랜잭션 경계 분리가 실제 프록시를 통해 동작하는지까지 함께 검증된다.
	@Autowired
	private StockDailyCandleImportWriter importWriter;

	// COLLECT-STAB-001과 동일한 이유로 실제 Redis 배선의 락을 그대로 재사용한다. 이 클래스의 시나리오는 순차
	// 호출(동시성 없음)이므로 매번 새 거래일에 처음 tryLock하는 한 항상 획득에 성공한다.
	@Autowired
	private StockCollectionLock stockCollectionLock;

	@Autowired
	private BusinessDayCalendar businessDayCalendar;

	private void setClock(LocalDate date, LocalTime time) {
		clock.set(LocalDateTime.of(date, time));
	}

	@AfterEach
	@Transactional
	void cleanUpDataCreatedByThisTest() {
		for (Instrument instrument : realStockInstruments()) {
			List<StockDailyCandle> candles = stockDailyCandleRepository
				.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAsc(
					instrument.getId(), LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1));
			if (!candles.isEmpty()) {
				stockDailyCandleRepository.deleteAll(candles);
			}
		}
		for (LocalDate anchorDate : List.of(TARGET_END_DATE_1, TARGET_END_DATE_2, TARGET_END_DATE_3)) {
			marketDataImportRepository
				.findFirstBySourceAndSourceTradingDateOrderByCollectedAtDesc(
					StockDailyCandleImportWriter.DATA_SOURCE, anchorDate)
				.ifPresent(marketDataImportRepository::delete);
		}
	}

	private static RawDailyCandleDto dailyCandle(LocalDate tradingDate, String open, String high, String low,
		String close) {
		return new RawDailyCandleDto(
			tradingDate, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close),
			1000L);
	}

	// KisDailyCandleCollector는 실제 REST 구현체(KIS 키 필요)가 아니라 이 Fake로만 구동한다 — 08:25 배치를 @Scheduled
	// 우회하고 서비스 메서드를 직접 호출하는 방식(MarketDataPipelineIntegrationTest와 동일한 관례).
	private StockDailyCandleCollector collectorWith(KisDailyCandleClient client) {
		return new StockDailyCandleCollector(
			instrumentRepository, client, stockDailyCandleRepository, importWriter, clock, businessDayCalendar,
			stockCollectionLock);
	}

	// KisDailyCandleClient가 특정 종목 호출에서만 예외를 던지는 상황을 흉내낸다 — 나머지 종목은 delegate(Fake)에
	// 그대로 위임한다. FakeKisDailyCandleClient는 실패를 표현할 수 없으므로(항상 정상 리스트 반환) 이 시나리오
	// 전용 더블을 따로 둔다(MarketDataPipelineIntegrationTest의 ThrowingKisHistoricalCandleClient와 동일 관례).
	private static final class SelectivelyThrowingKisDailyCandleClient implements KisDailyCandleClient {
		private final KisDailyCandleClient delegate;
		private final String throwingSymbol;

		SelectivelyThrowingKisDailyCandleClient(KisDailyCandleClient delegate, String throwingSymbol) {
			this.delegate = delegate;
			this.throwingSymbol = throwingSymbol;
		}

		@Override
		public List<RawDailyCandleDto> fetchDailyCandles(String symbol, LocalDate from, LocalDate to) {
			if (symbol.equals(throwingSymbol)) {
				throw new IllegalStateException("응답 파싱 실패: 지원하지 않는 응답 구조");
			}
			return delegate.fetchDailyCandles(symbol, from, to);
		}
	}

	private List<StockDailyCandle> allCandlesFor(Instrument instrument) {
		return stockDailyCandleRepository.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAsc(
			instrument.getId(), LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1));
	}

	@Test
	void firstRunFillsThreeYearGapIncrementalRunAddsOneDayAndRerunIsIdempotent() {
		Instrument instrument = realStockInstrument(0);

		// 최초 실행 — 저장된 일봉이 없어 "3년 전 ~ 직전 영업일" 전체가 빈 구간으로 계산된다(결정 1, plan.md).
		// 실제 3년치 750건 대신 상징적으로 3건만 채운다(페이징 자체는 task 3에서 이미 검증).
		setClock(SERVICE_DATE_1, LocalTime.of(8, 25));
		FakeKisDailyCandleClient fakeClient = new FakeKisDailyCandleClient();
		fakeClient.setCandles(instrument.getSymbol(), List.of(
			dailyCandle(ARCHIVE_ROW_2024, "70000", "70500", "69800", "70200"),
			dailyCandle(ARCHIVE_ROW_2025, "72000", "72500", "71800", "72300"),
			dailyCandle(TARGET_END_DATE_1, "75000", "75500", "74800", "75300")));
		collectorWith(fakeClient).collect();

		List<MarketDataImport> importsAfterFirstRun = marketDataImportRepository
			.findFirstBySourceAndSourceTradingDateOrderByCollectedAtDesc(
				StockDailyCandleImportWriter.DATA_SOURCE, TARGET_END_DATE_1)
			.map(List::of)
			.orElse(List.of());
		assertThat(importsAfterFirstRun).hasSize(1);
		// 공유 DB에 다른 테스트가 남긴 종목이 섞여 있을 수 있어(6자리 숫자가 아닌 심볼) 전체 상태가 SUCCESS인지는
		// 단정하지 않는다 — 이 시나리오의 대상 종목만은 실패 목록에 없다는 것으로 충분히 확인한다.
		assertThat(importsAfterFirstRun.get(0).getStatus()).isNotEqualTo(ImportStatus.FAILED);
		if (importsAfterFirstRun.get(0).getFailureReason() != null) {
			assertThat(importsAfterFirstRun.get(0).getFailureReason()).doesNotContain(instrument.getSymbol());
		}
		assertThat(allCandlesFor(instrument)).hasSize(3);

		// 증분 실행 — 이미 TARGET_END_DATE_1까지 저장돼 있으므로 빈 구간은 [TARGET_END_DATE_1+1, TARGET_END_DATE_2]
		// = TARGET_END_DATE_2 하루뿐이다(STOCK-DAILY-007).
		setClock(SERVICE_DATE_2, LocalTime.of(8, 25));
		fakeClient.setCandles(instrument.getSymbol(), List.of(
			dailyCandle(TARGET_END_DATE_2, "76000", "76500", "75800", "76300")));
		collectorWith(fakeClient).collect();

		assertThat(allCandlesFor(instrument)).hasSize(4);
		assertThat(allCandlesFor(instrument).get(3).getTradingDate()).isEqualTo(TARGET_END_DATE_2);

		// 재실행 — 같은 targetEndDate(TARGET_END_DATE_2)에 이미 최신까지 저장돼 있어 빈 구간이 없다. Fake에는
		// 여전히 위 1건이 설정돼 있지만, computeRangeStart가 null을 반환해 KIS 호출 자체가 일어나지 않으므로
		// 행 수는 그대로다(UNIQUE(instrument_id, trading_date) 멱등성, STOCK-DAILY-006).
		collectorWith(fakeClient).collect();

		assertThat(allCandlesFor(instrument)).hasSize(4);
	}

	@Test
	void oneInstrumentStructuralFailureStillSavesOtherInstrumentsAndLeavesStockCandlesUntouched() {
		Instrument goodInstrument = realStockInstrument(1);
		Instrument brokenInstrument = realStockInstrument(2);

		long stockCandleCountBeforeBatch = stockCandleRepository.count();

		setClock(SERVICE_DATE_3, LocalTime.of(8, 25));
		FakeKisDailyCandleClient fakeClient = new FakeKisDailyCandleClient();
		fakeClient.setCandles(goodInstrument.getSymbol(), List.of(
			dailyCandle(TARGET_END_DATE_3, "50000", "50500", "49800", "50300")));
		SelectivelyThrowingKisDailyCandleClient throwingClient = new SelectivelyThrowingKisDailyCandleClient(
			fakeClient, brokenInstrument.getSymbol());

		collectorWith(throwingClient).collect();

		List<MarketDataImport> imports = marketDataImportRepository
			.findFirstBySourceAndSourceTradingDateOrderByCollectedAtDesc(
				StockDailyCandleImportWriter.DATA_SOURCE, TARGET_END_DATE_3)
			.map(List::of)
			.orElse(List.of());
		assertThat(imports).hasSize(1);
		// 대상 종목 중 하나가 실패했으므로 전체 결과는 최소한 실패 종목을 포함해야 한다(다른 잔여 종목의 상태와
		// 무관하게 FAILED까지는 아니어야 한다 — goodInstrument가 성공했으므로 전체 FAILED일 수 없다).
		assertThat(imports.get(0).getStatus()).isNotEqualTo(ImportStatus.FAILED);
		assertThat(imports.get(0).getFailureReason()).contains(brokenInstrument.getSymbol());

		assertThat(allCandlesFor(goodInstrument)).hasSize(1);
		assertThat(allCandlesFor(goodInstrument).get(0).getTradingDate()).isEqualTo(TARGET_END_DATE_3);
		assertThat(allCandlesFor(brokenInstrument)).isEmpty();

		// STOCK-DAILY-005 회귀 — 일봉 아카이브 배치가 1분봉 테이블(stock_candles) 행 수를 하나도 바꾸지 않는다.
		assertThat(stockCandleRepository.count()).isEqualTo(stockCandleCountBeforeBatch);
	}
}
