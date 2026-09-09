package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.entity.ImportStatus;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.MarketDataImport;
import com.finplay.api.domain.market.entity.StockDailyCandle;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.MarketDataImportRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import com.finplay.api.domain.market.repository.StockDailyCandleRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
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

	private List<Instrument> realStockInstruments() {
		return instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK);
	}

	private Instrument realStockInstrument(int index) {
		return realStockInstruments().get(index);
	}

	private static final LocalDate ARCHIVE_ROW_2024 = LocalDate.of(2024, 7, 15);
	private static final LocalDate ARCHIVE_ROW_2025 = LocalDate.of(2025, 7, 15);
	private static final LocalDate TARGET_END_DATE_1 = LocalDate.of(2026, 7, 13);
	private static final LocalDate SERVICE_DATE_1 = LocalDate.of(2026, 7, 14);
	private static final LocalDate TARGET_END_DATE_2 = LocalDate.of(2026, 7, 14);
	private static final LocalDate SERVICE_DATE_2 = LocalDate.of(2026, 7, 15);

	private static final LocalDate TARGET_END_DATE_3 = LocalDate.of(2026, 7, 15);
	private static final LocalDate SERVICE_DATE_3 = LocalDate.of(2026, 7, 16);

	@Autowired
	private TestClock clock;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockDailyCandleRepository stockDailyCandleRepository;

	@Autowired
	private MarketDataImportRepository marketDataImportRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private StockDailyCandleImportWriter importWriter;

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

	private StockDailyCandleCollector collectorWith(KisDailyCandleClient client) {
		return new StockDailyCandleCollector(
			instrumentRepository, client, stockDailyCandleRepository, importWriter, clock, businessDayCalendar,
			stockCollectionLock);
	}

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
		assertThat(importsAfterFirstRun.get(0).getStatus()).isNotEqualTo(ImportStatus.FAILED);
		if (importsAfterFirstRun.get(0).getFailureReason() != null) {
			assertThat(importsAfterFirstRun.get(0).getFailureReason()).doesNotContain(instrument.getSymbol());
		}
		assertThat(allCandlesFor(instrument)).hasSize(3);

		setClock(SERVICE_DATE_2, LocalTime.of(8, 25));
		fakeClient.setCandles(instrument.getSymbol(), List.of(
			dailyCandle(TARGET_END_DATE_2, "76000", "76500", "75800", "76300")));
		collectorWith(fakeClient).collect();

		assertThat(allCandlesFor(instrument)).hasSize(4);
		assertThat(allCandlesFor(instrument).get(3).getTradingDate()).isEqualTo(TARGET_END_DATE_2);

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
		assertThat(imports.get(0).getStatus()).isNotEqualTo(ImportStatus.FAILED);
		assertThat(imports.get(0).getFailureReason()).contains(brokenInstrument.getSymbol());

		assertThat(allCandlesFor(goodInstrument)).hasSize(1);
		assertThat(allCandlesFor(goodInstrument).get(0).getTradingDate()).isEqualTo(TARGET_END_DATE_3);
		assertThat(allCandlesFor(brokenInstrument)).isEmpty();

		assertThat(stockCandleRepository.count()).isEqualTo(stockCandleCountBeforeBatch);
	}
}
