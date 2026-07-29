// KIS 과거 분봉을 명백한 오류만 검증해 정규화된 StockCandle로 저장하고 MarketDataImport 이력을 남기는 평일 08:10 KST 수집 배치
package com.finplay.api.market.service;

import com.finplay.api.market.domain.ImportStatus;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.MarketDataImport;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.MarketDataImportRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 이 클래스가 검증하는 항목(spec.md MKT-005)과 검증 범위를 명확히 구분한다.
//  - 종목 단위(atomic): 한 종목의 분봉 중 하나라도 아래 구조 오류가 있으면 그 종목의 그날 분봉 전체를 저장하지 않는다.
//  - 전체 단위: KisHistoricalCandleClient 호출 자체가 예외를 던지면(응답 파싱 불가 등) 이번 수집 전체를 미저장·FAILED 처리한다.
// 두 가지는 아래 이유로 이번 클라이언트 경계(RawMinuteCandle)에서 항상 구조적으로 만족되어 별도 런타임 검사를 두지 않는다.
//  - "MVP 허용 16종 여부": InstrumentRepository.findByMarketOrderByIdAsc(Market.STOCK)로만 순회하므로 애초에 허용 종목만 대상이다.
//  - "조회 대상 거래일과 응답 거래일 일치": KisHistoricalCandleClient는 이번 Decision Gate(plan.md — output2 필드명은
//    체결시각·시가·고가·저가·종가·거래량 6종만 확인 대상)에서 행별 거래일 필드를 노출하지 않는다. Collector는 항상 단일
//    tradingDate로 조회하고 그 값을 모든 StockCandle에 그대로 정규화하므로 이 경계 안에서는 불일치가 발생할 수 없다.
//    실제 KIS 응답이 다른 거래일을 섞어 반환하는 경우가 확인되면, 그 교정은 output2 파싱이 격리된 KisHistoricalCandleClient
//    구현체 쪽에서 다뤄야 한다(임의로 필드를 추측해 이 경계를 넓히지 않는다).
@Service
@RequiredArgsConstructor
@Slf4j
public class KisHistoricalCandleCollector {

	private static final String DATA_SOURCE = "KIS";
	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);
	private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);
	private static final Pattern STOCK_SYMBOL_PATTERN = Pattern.compile("^\\d{6}$");
	private static final int MAX_FAILURE_REASON_LENGTH = 500;
	private static final String HOLIDAYS_RESOURCE_PATH = "/holidays-2026.txt";

	// StockReplayService와 별개의 클래스가 각자의 목적(재생세션 개장 판정 vs. 수집 대상 거래일 계산)으로 같은 리소스 파일을
	// 읽는다 — 두 번째 중복이라 공통화하지 않는다(conventions.md: 공통화는 세 번째 중복부터 검토).
	private static final Set<LocalDate> HOLIDAYS_2026 = loadHolidays(HOLIDAYS_RESOURCE_PATH);

	private final InstrumentRepository instrumentRepository;
	private final KisHistoricalCandleClient kisHistoricalCandleClient;
	private final StockCandleRepository stockCandleRepository;
	private final MarketDataImportRepository marketDataImportRepository;
	private final Clock clock;

	// 평일 08:10 KST 실행 — 당일 분봉이 익영업일 오전 8시경 제공된다는 확인 결과에 여유를 둔 시각(plan.md "배치 실행 시각").
	@Scheduled(cron = "0 10 8 * * MON-FRI", zone = "Asia/Seoul")
	@Transactional
	public void collect() {
		LocalDate tradingDate = resolvePreviousBusinessDay(LocalDate.now(clock));
		LocalDateTime collectedAt = LocalDateTime.now(clock);
		List<Instrument> stockInstruments = instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK);
		try {
			List<InstrumentOutcome> outcomes = new ArrayList<>();
			for (Instrument instrument : stockInstruments) {
				outcomes.add(collectInstrument(instrument, tradingDate, collectedAt));
			}
			persist(tradingDate, collectedAt, outcomes);
		} catch (RuntimeException ex) {
			// KisHistoricalCandleClient가 던지는 예외(응답 파싱 불가·지원하지 않는 응답 구조 등)는 종목 단위 구조 오류와
			// 달리 전체 응답 자체를 신뢰할 수 없다는 뜻이므로, 이번 실행 전체를 미저장·FAILED로 남긴다 — 이전에 계산해 둔
			// 다른 종목의 결과가 있어도(outcomes) 여기서는 저장하지 않는다(스코프 밖 persist 호출 자체가 없으므로 안전).
			log.error("KIS 과거 분봉 수집이 전체 응답 오류로 중단되었습니다 (tradingDate={})", tradingDate, ex);
			marketDataImportRepository.save(MarketDataImport.create(
				DATA_SOURCE, tradingDate, collectedAt, ImportStatus.FAILED,
				truncateReason("전체 응답 오류로 수집이 중단되었습니다: " + ex.getMessage())));
		}
	}

	private InstrumentOutcome collectInstrument(Instrument instrument, LocalDate tradingDate,
		LocalDateTime collectedAt) {
		boolean alreadyCollected = !stockCandleRepository
			.findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(instrument.getId(), tradingDate)
			.isEmpty();
		if (alreadyCollected) {
			// UNIQUE(instrument_id, trading_date, candle_time) 기반 재실행 멱등성 — 이미 저장된 종목·거래일은 다시
			// 조회·저장하지 않는다. 동일/상충 재수집 판정(SKIPPED_DUPLICATE 등, 이슈 #83)은 이번 범위가 아니다.
			return new InstrumentOutcome(instrument, List.of(), null);
		}

		List<RawMinuteCandle> rawCandles = kisHistoricalCandleClient.fetchMinuteCandles(instrument.getSymbol(),
			tradingDate);
		String failureReason = validateInstrumentCandles(instrument, rawCandles);
		if (failureReason != null) {
			return new InstrumentOutcome(instrument, List.of(), failureReason);
		}

		List<StockCandle> candles = rawCandles.stream()
			.map(raw -> toStockCandle(instrument, tradingDate, raw, collectedAt))
			.toList();
		return new InstrumentOutcome(instrument, candles, null);
	}

	// 종목 하나의 분봉 목록 전체를 검증한다 — 하나라도 위반하면 그 종목의 그날 분봉 전체를 원자적으로 저장하지 않는다.
	// 분봉 개수가 적거나 0건인 것 자체는 오류로 보지 않는다(실제 거래정지·거래 없음·빈 분 생략 가능성 — spec.md, 임계치는
	// Decision Gate이므로 이 클래스에서 임의 숫자를 도입하지 않는다).
	private String validateInstrumentCandles(Instrument instrument, List<RawMinuteCandle> rawCandles) {
		if (!STOCK_SYMBOL_PATTERN.matcher(instrument.getSymbol()).matches()) {
			return "종목코드 형식이 올바르지 않습니다: " + instrument.getSymbol();
		}
		Set<LocalTime> seenCandleTimes = new HashSet<>();
		for (RawMinuteCandle candle : rawCandles) {
			String rowFailureReason = validateRow(candle, seenCandleTimes);
			if (rowFailureReason != null) {
				return rowFailureReason;
			}
		}
		return null;
	}

	private String validateRow(RawMinuteCandle candle, Set<LocalTime> seenCandleTimes) {
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
		Instrument instrument, LocalDate tradingDate, RawMinuteCandle raw, LocalDateTime collectedAt) {
		return StockCandle.create(
			instrument, tradingDate, raw.candleTime(), raw.open(), raw.high(), raw.low(), raw.close(),
			raw.volume(), DATA_SOURCE, collectedAt);
	}

	private void persist(LocalDate tradingDate, LocalDateTime collectedAt, List<InstrumentOutcome> outcomes) {
		List<InstrumentOutcome> failedOutcomes = outcomes.stream().filter(outcome -> outcome.failureReason() != null)
			.toList();
		List<InstrumentOutcome> succeededOutcomes = outcomes.stream().filter(outcome -> outcome.failureReason() == null)
			.toList();

		for (InstrumentOutcome outcome : succeededOutcomes) {
			if (!outcome.candles().isEmpty()) {
				stockCandleRepository.saveAll(outcome.candles());
			}
		}

		ImportStatus status;
		String failureReason = null;
		if (failedOutcomes.isEmpty()) {
			status = ImportStatus.SUCCESS;
		} else if (succeededOutcomes.isEmpty()) {
			// 대상 종목 전부가 구조 오류라면 "부분" 성공이 아니라 사실상 전체 실패다 — StockCandle은 어차피 하나도 저장되지
			// 않으므로(succeededOutcomes가 비어 있음) PARTIAL_SUCCESS로 표시하지 않는다.
			status = ImportStatus.FAILED;
			failureReason = summarizeFailures(failedOutcomes);
		} else {
			status = ImportStatus.PARTIAL_SUCCESS;
			failureReason = summarizeFailures(failedOutcomes);
		}
		marketDataImportRepository.save(
			MarketDataImport.create(DATA_SOURCE, tradingDate, collectedAt, status, failureReason));
	}

	private static String summarizeFailures(List<InstrumentOutcome> failedOutcomes) {
		String joined = failedOutcomes.stream()
			.map(outcome -> outcome.instrument().getSymbol() + ": " + outcome.failureReason())
			.collect(Collectors.joining("; "));
		return truncateReason(joined);
	}

	private static String truncateReason(String reason) {
		if (reason == null || reason.length() <= MAX_FAILURE_REASON_LENGTH) {
			return reason;
		}
		return reason.substring(0, MAX_FAILURE_REASON_LENGTH);
	}

	// 오늘(from)의 직전 영업일을 계산한다 — 주말·공휴일(리소스 파일 기준)을 건너뛴다. 직전 영업일 데이터가 아직 준비되지
	// 않았을 때의 폴백은 StockReplaySessionScheduler(이슈 #19 ⑥)의 책임이며, 이 메서드는 오늘 기준 하루 전 영업일 하나만
	// 계산한다.
	private static LocalDate resolvePreviousBusinessDay(LocalDate from) {
		LocalDate candidate = from.minusDays(1);
		while (isWeekend(candidate) || HOLIDAYS_2026.contains(candidate)) {
			candidate = candidate.minusDays(1);
		}
		return candidate;
	}

	private static boolean isWeekend(LocalDate date) {
		DayOfWeek dayOfWeek = date.getDayOfWeek();
		return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
	}

	// 클래스패스 리소스 파일에서 공휴일 목록(한 줄에 yyyy-MM-dd, #으로 시작하는 줄은 주석)을 읽어 Set으로 반환한다.
	private static Set<LocalDate> loadHolidays(String resourcePath) {
		try (InputStream inputStream = KisHistoricalCandleCollector.class.getResourceAsStream(resourcePath)) {
			if (inputStream == null) {
				throw new IllegalStateException("공휴일 리소스 파일을 찾을 수 없습니다: " + resourcePath);
			}
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
				return reader
					.lines()
					.map(String::strip)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.map(LocalDate::parse)
					.collect(Collectors.toUnmodifiableSet());
			}
		} catch (IOException ex) {
			throw new IllegalStateException("공휴일 리소스 파일을 읽는 중 오류가 발생했습니다: " + resourcePath, ex);
		}
	}

	// 종목 하나의 수집 결과 — failureReason이 null이면 구조 오류 없음(candles가 비어 있을 수도 있다: 이미 저장돼 있어
	// 건너뛴 경우, 또는 실제로 그날 분봉이 없는 경우 모두 정상 케이스로 취급한다).
	private record InstrumentOutcome(Instrument instrument, List<StockCandle> candles, String failureReason) {
		// 컬렉션 필드를 가진 record는 방어적 복사가 기본이다 (agent-mistakes.md 2026-07-29 — spotbugsMain EI_EXPOSE_REP).
		private InstrumentOutcome {
			candles = List.copyOf(candles);
		}
	}
}
