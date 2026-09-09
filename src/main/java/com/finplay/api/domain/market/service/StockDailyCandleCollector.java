package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockDailyCandle;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockDailyCandleRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockDailyCandleCollector {

	private static final long ARCHIVE_YEARS = 3L;
	private static final Pattern STOCK_SYMBOL_PATTERN = Pattern.compile("^\\d{6}$");

	private final InstrumentRepository instrumentRepository;
	private final KisDailyCandleClient kisDailyCandleClient;
	private final StockDailyCandleRepository stockDailyCandleRepository;
	private final StockDailyCandleImportWriter importWriter;
	private final Clock clock;
	private final BusinessDayCalendar businessDayCalendar;
	private final StockCollectionLock stockCollectionLock;

	@Scheduled(cron = "0 25 8 * * MON-FRI", zone = "Asia/Seoul")
	public void collect() {
		LocalDate targetEndDate = businessDayCalendar.previousBusinessDay(LocalDate.now(clock));
		Optional<String> lockToken = stockCollectionLock.tryLock(targetEndDate);
		if (lockToken.isEmpty()) {
			log.info("주식 일봉 아카이브 수집 락을 얻지 못해 이번 실행을 건너뜁니다(다른 실행이 이미 처리 중) - targetEndDate={}",
				targetEndDate);
			return;
		}
		try {
			LocalDateTime collectedAt = LocalDateTime.now(clock);
			try {
				List<Instrument> stockInstruments = instrumentRepository
					.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK);
				List<DailyInstrumentOutcome> outcomes = new ArrayList<>();
				for (Instrument instrument : stockInstruments) {
					outcomes.add(collectInstrument(instrument, targetEndDate, collectedAt));
				}
				importWriter.persist(targetEndDate, collectedAt, outcomes);
			} catch (RuntimeException ex) {
				log.error("KIS 일봉 아카이브 수집이 종목 단위로 좁힐 수 없는 오류로 중단되었습니다 (targetEndDate={})", targetEndDate, ex);
				importWriter.recordFailedImport(targetEndDate, collectedAt,
					"수집이 예상치 못한 오류로 중단되었습니다: " + ex.getMessage());
			}
		} finally {
			stockCollectionLock.unlock(targetEndDate, lockToken.get());
		}
	}

	private DailyInstrumentOutcome collectInstrument(
		Instrument instrument, LocalDate targetEndDate, LocalDateTime collectedAt) {
		if (!STOCK_SYMBOL_PATTERN.matcher(instrument.getSymbol()).matches()) {
			return new DailyInstrumentOutcome(instrument, List.of(), "종목코드 형식이 올바르지 않습니다: " + instrument.getSymbol());
		}

		LocalDate rangeStart = computeRangeStart(instrument, targetEndDate);
		if (rangeStart == null) {
			return new DailyInstrumentOutcome(instrument, List.of(), null);
		}

		List<RawDailyCandleDto> rawCandles;
		try {
			rawCandles = kisDailyCandleClient.fetchDailyCandles(instrument.getSymbol(), rangeStart, targetEndDate);
		} catch (RuntimeException ex) {
			log.warn("KIS 일봉 조회가 종목 단위로 실패했습니다 (symbol={}, rangeStart={}, targetEndDate={})",
				instrument.getSymbol(), rangeStart, targetEndDate, ex);
			return new DailyInstrumentOutcome(instrument, List.of(), "일봉 조회 중 오류가 발생했습니다: " + ex.getMessage());
		}

		List<StockDailyCandle> candles = rawCandles.stream()
			.map(raw -> toStockDailyCandle(instrument, raw, collectedAt))
			.toList();
		return new DailyInstrumentOutcome(instrument, candles, null);
	}

	private LocalDate computeRangeStart(Instrument instrument, LocalDate targetEndDate) {
		Optional<StockDailyCandle> latest = stockDailyCandleRepository
			.findFirstByInstrumentIdOrderByTradingDateDesc(instrument.getId());
		LocalDate rangeStart = latest
			.map(candle -> candle.getTradingDate().plusDays(1))
			.orElseGet(() -> targetEndDate.minusYears(ARCHIVE_YEARS));
		if (rangeStart.isAfter(targetEndDate)) {
			return null;
		}
		return rangeStart;
	}

	private static StockDailyCandle toStockDailyCandle(
		Instrument instrument, RawDailyCandleDto raw, LocalDateTime collectedAt) {
		return StockDailyCandle.create(
			instrument, raw.tradingDate(), raw.open(), raw.high(), raw.low(), raw.close(), raw.volume(),
			StockDailyCandleImportWriter.DATA_SOURCE, collectedAt);
	}
}
