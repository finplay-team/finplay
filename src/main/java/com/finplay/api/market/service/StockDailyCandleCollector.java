// KIS 일봉을 종목별 "있어야 할 구간 − 이미 저장된 구간"만 조회해 StockDailyCandle로 저장하고 MarketDataImport
// 이력을 남기는 평일 08:25 KST 수집 배치. 같은 대상 거래일 동시 실행은 StockCollectionLock(Redis 분산 락)으로 배제한다.
package com.finplay.api.market.service;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockDailyCandle;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockDailyCandleRepository;
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

// 이 클래스가 하는 일(spec 050 STOCK-DAILY-002·007·008)과 하지 않는 일을 구분한다.
//  - 빈 구간 계산(결정 1, plan.md): 종목마다 "있어야 할 구간(3년 전 ~ 직전 영업일) − 이미 저장된 구간"만 계산해
//    KisDailyCandleClient에 조회를 요청한다. 최초 실행은 3년 전량, 정상 운영은 직전 영업일 1건, 재실행은 채울
//    구간이 없어 KIS를 호출하지 않는다 — StockDailyCandleRepository.findFirstByInstrumentIdOrderByTradingDateDesc로
//    "이미 저장된 구간의 끝"만 확인하면 충분하다(UNIQUE(instrument_id, trading_date)로 그 이전 날짜는 항상 이미
//    채워져 있다고 가정할 수 있으므로 — 과거 일봉은 확정된 기록이라 수정·재수집하지 않는다는 spec 비즈니스 규칙).
//  - 종목 단위(atomic) 실패 격리: 한 종목의 KIS 호출 실패·심볼 형식 오류가 다른 종목의 수집을 막지 않는다
//    (STOCK-DAILY-008, KisHistoricalCandleCollector와 동일 패턴).
//  - 응답 행 검증(OHLC 존재·양수, low≤open·close≤high, 거래량 0 이상, 구간 이탈)은 KisDailyCandleClientImpl 쪽
//    경계에서 이미 위반 행만 걸러 반환한다(task 3) — 이 클래스는 그 결과를 그대로 신뢰한다. 거래일 수가 적은 것
//    자체는 오류가 아니므로(거래정지·신규상장 가능) 개수만으로 실패 판정하지 않는다(spec.md 비즈니스 규칙).
@Service
@RequiredArgsConstructor
@Slf4j
public class StockDailyCandleCollector {

	// 최근 3년(spec STOCK-DAILY-004, plan.md 데이터 모델) — 저장된 일봉이 없는 종목의 최초 적재 시작점 산정에 쓴다.
	private static final long ARCHIVE_YEARS = 3L;
	private static final Pattern STOCK_SYMBOL_PATTERN = Pattern.compile("^\\d{6}$");

	private final InstrumentRepository instrumentRepository;
	private final KisDailyCandleClient kisDailyCandleClient;
	private final StockDailyCandleRepository stockDailyCandleRepository;
	private final StockDailyCandleImportWriter importWriter;
	private final Clock clock;
	private final BusinessDayCalendar businessDayCalendar;
	private final StockCollectionLock stockCollectionLock;

	// 08:25 KST — 1분봉 정규 배치(08:10)가 끝날 여유 시간을 두면서도, 1분봉 재시도(market.stock.retry-cron,
	// 08:15/08:30/08:45)와 재생세션 확정 배치(08:40)의 실행 시각과는 겹치지 않는다(plan.md "구성요소" 절 확정, 2026-08-20).
	// StockCollectionLock을 그대로 재사용하므로(STOCK-DAILY-011) targetEndDate가 1분봉 배치와 같은 날이면 Redis
	// 락 키(market:stock-collect:lock:{date})도 같다 — 두 배치가 같은 거래일의 시장 데이터를 동시에 건드리지
	// 않는다는 의미로는 자연스럽고, 1분봉 배치가 TTL(600초) 안에 정상 종료되는 한 08:25에는 이미 풀려 있다. 드물게
	// 못 얻더라도(COLLECT-STAB-001과 동일하게 조용히 스킵) 다음날 실행이 빈 구간을 그대로 이어서 채우므로 데이터
	// 유실은 없다.
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
				// 종목별 KIS 호출 실패는 collectInstrument 내부에서 이미 그 종목 하나만의 실패로 흡수된다 — 여기까지
				// 올라오는 예외는 종목 하나로 좁힐 수 없는 진짜 전체 오류다(종목 목록 조회 실패, 저장 트랜잭션 실패 등).
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
			// 채울 구간이 없다 — 이미 targetEndDate까지 저장돼 있다(STOCK-DAILY-007 재실행 멱등, KIS 미호출).
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

	// 결정 1(plan.md) — "있어야 할 구간(3년 전 ~ targetEndDate) − 이미 저장된 구간"을 계산한다. 저장된 일봉이
	// 없으면 3년 전부터, 있으면 최신 저장 거래일의 다음 날부터. 채울 구간이 없으면(이미 최신) null을 반환한다.
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
