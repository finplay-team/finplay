package com.finplay.api.domain.market.service;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockCandle;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockCandleRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisHistoricalCandleCollector {

	private static final String DATA_SOURCE = "KIS";
	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);
	private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);
	private static final Pattern STOCK_SYMBOL_PATTERN = Pattern.compile("^\\d{6}$");

	private final InstrumentRepository instrumentRepository;
	private final KisHistoricalCandleClient kisHistoricalCandleClient;
	private final StockCandleRepository stockCandleRepository;
	private final KisHistoricalCandleImportWriter importWriter;
	private final Clock clock;
	private final BusinessDayCalendar businessDayCalendar;
	private final StockCollectionLock stockCollectionLock;

	@Scheduled(cron = "0 10 8 * * MON-FRI", zone = "Asia/Seoul")
	public void collect() {
		LocalDate tradingDate = businessDayCalendar.previousBusinessDay(LocalDate.now(clock));
		Optional<String> lockToken = stockCollectionLock.tryLock(tradingDate);
		if (lockToken.isEmpty()) {
			log.info("주식 분봉 수집 락을 얻지 못해 이번 실행을 건너뜁니다(다른 실행이 이미 처리 중) - tradingDate={}", tradingDate);
			return;
		}
		try {
			LocalDateTime collectedAt = LocalDateTime.now(clock);
			try {
				List<Instrument> stockInstruments = instrumentRepository
					.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK);
				List<InstrumentOutcome> outcomes = new ArrayList<>();
				for (Instrument instrument : stockInstruments) {
					outcomes.add(collectInstrument(instrument, tradingDate, collectedAt));
				}
				importWriter.persist(tradingDate, collectedAt, outcomes);
			} catch (RuntimeException ex) {
				log.error("KIS 과거 분봉 수집이 종목 단위로 좁힐 수 없는 오류로 중단되었습니다 (tradingDate={})", tradingDate, ex);
				importWriter.recordFailedImport(tradingDate, collectedAt,
					"수집이 예상치 못한 오류로 중단되었습니다: " + ex.getMessage());
			}
		} finally {
			stockCollectionLock.unlock(tradingDate, lockToken.get());
		}
	}

	@Scheduled(cron = "${market.stock.retry-cron}", zone = "Asia/Seoul")
	public void retryPendingInstruments() {
		collect();
	}

	private InstrumentOutcome collectInstrument(Instrument instrument, LocalDate tradingDate,
		LocalDateTime collectedAt) {
		boolean alreadyCollected = stockCandleRepository
			.existsByInstrumentIdAndTradingDate(instrument.getId(), tradingDate);
		if (alreadyCollected) {
			return new InstrumentOutcome(instrument, List.of(), null);
		}

		List<RawMinuteCandleDto> rawCandles;
		try {
			rawCandles = kisHistoricalCandleClient.fetchMinuteCandles(instrument.getSymbol(), tradingDate);
		} catch (RuntimeException ex) {
			log.warn("KIS 과거 분봉 조회가 종목 단위로 실패했습니다 (symbol={}, tradingDate={})", instrument.getSymbol(),
				tradingDate, ex);
			return new InstrumentOutcome(instrument, List.of(), "분봉 조회 중 오류가 발생했습니다: " + ex.getMessage());
		}

		String failureReason = validateInstrumentCandles(instrument, rawCandles);
		if (failureReason != null) {
			return new InstrumentOutcome(instrument, List.of(), failureReason);
		}

		List<StockCandle> candles = rawCandles.stream()
			.map(raw -> toStockCandle(instrument, tradingDate, raw, collectedAt))
			.toList();
		return new InstrumentOutcome(instrument, candles, null);
	}

	private String validateInstrumentCandles(Instrument instrument, List<RawMinuteCandleDto> rawCandles) {
		if (!STOCK_SYMBOL_PATTERN.matcher(instrument.getSymbol()).matches()) {
			return "종목코드 형식이 올바르지 않습니다: " + instrument.getSymbol();
		}
		Set<LocalTime> seenCandleTimes = new HashSet<>();
		for (RawMinuteCandleDto candle : rawCandles) {
			String rowFailureReason = validateRow(candle, seenCandleTimes);
			if (rowFailureReason != null) {
				return rowFailureReason;
			}
		}
		return null;
	}

	private String validateRow(RawMinuteCandleDto candle, Set<LocalTime> seenCandleTimes) {
		if (candle.candleTime() == null || candle.open() == null || candle.high() == null
			|| candle.low() == null || candle.close() == null || candle.volume() == null) {
			return "필수 필드가 누락된 분봉이 있습니다.";
		}
		if (!seenCandleTimes.add(candle.candleTime())) {
			return "동일 분봉시각이 중복되었습니다: " + candle.candleTime();
		}
		if (candle.candleTime().isBefore(MARKET_OPEN_TIME) || candle.candleTime().isAfter(MARKET_CLOSE_TIME)) {
			return "분봉시각이 시장 시간 범위(09:00~15:30)를 벗어났습니다: " + candle.candleTime();
		}
		if (isNegative(candle.open()) || isNegative(candle.high()) || isNegative(candle.low())
			|| isNegative(candle.close()) || candle.volume() < 0) {
			return "가격 또는 거래량이 음수인 분봉이 있습니다: " + candle.candleTime();
		}
		if (candle.high().compareTo(candle.open()) < 0 || candle.high().compareTo(candle.close()) < 0
			|| candle.high().compareTo(candle.low()) < 0) {
			return "고가가 시가·종가·저가보다 낮은 분봉이 있습니다: " + candle.candleTime();
		}
		if (candle.low().compareTo(candle.open()) > 0 || candle.low().compareTo(candle.close()) > 0) {
			return "저가가 시가·종가보다 높은 분봉이 있습니다: " + candle.candleTime();
		}
		return null;
	}

	private static boolean isNegative(BigDecimal value) {
		return value.signum() < 0;
	}

	private static StockCandle toStockCandle(
		Instrument instrument, LocalDate tradingDate, RawMinuteCandleDto raw, LocalDateTime collectedAt) {
		return StockCandle.create(
			instrument, tradingDate, raw.candleTime(), raw.open(), raw.high(), raw.low(), raw.close(),
			raw.volume(), DATA_SOURCE, collectedAt);
	}
}
