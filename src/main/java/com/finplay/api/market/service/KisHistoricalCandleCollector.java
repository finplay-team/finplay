// KIS 과거 분봉을 명백한 오류만 검증해 정규화된 StockCandle로 저장하고 MarketDataImport 이력을 남기는 평일 08:10 KST 수집 배치.
// 같은 거래일 동시 실행은 StockCollectionLock(Redis 분산 락)으로 배제한다(COLLECT-STAB-001).
package com.finplay.api.market.service;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockCandleRepository;
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

// 이 클래스가 검증하는 항목(spec.md MKT-005)과 검증 범위를 명확히 구분한다.
//  - 종목 단위(atomic): 한 종목의 분봉 중 하나라도 아래 구조 오류가 있으면 그 종목의 그날 분봉 전체를 저장하지 않는다.
//    KisHistoricalCandleClient.fetchMinuteCandles 호출 자체가 예외를 던지는 경우(타임아웃 등 일시적 네트워크 실패
//    포함)도 그 종목 하나만의 실패로 흡수한다 — 나머지 종목은 계속 수집을 진행한다(PR #94 리뷰 권장사항 ③).
//  - 전체 단위: 종목 하나로 좁힐 수 없는 진짜 전체 오류(종목 목록 조회 실패, 검증·저장 로직 자체의 버그 등)만 collect()의
//    최상위 catch에서 이번 실행 전체를 미저장·FAILED로 남긴다.
// 아래 두 가지는 이번 클라이언트 경계(RawMinuteCandleDto)에서 항상 구조적으로 만족되어 별도 런타임 검사를 두지 않는다.
//  - "MVP 허용 16종 여부": InstrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK)로만
//    순회하므로 애초에 허용 종목(샌드박스 튜토리얼 종목 제외)만 대상이다.
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

	private final InstrumentRepository instrumentRepository;
	private final KisHistoricalCandleClient kisHistoricalCandleClient;
	private final StockCandleRepository stockCandleRepository;
	private final KisHistoricalCandleImportWriter importWriter;
	private final Clock clock;
	private final BusinessDayCalendar businessDayCalendar;
	private final StockCollectionLock stockCollectionLock;

	// 평일 08:10 KST 실행 — 당일 분봉이 익영업일 오전 8시경 제공된다는 확인 결과에 여유를 둔 시각(plan.md "배치 실행 시각").
	// 이 메서드 자체는 트랜잭션을 열지 않는다 — 종목별 KIS HTTP 호출(최대 10페이지, connect 5s/read 10s 타임아웃)이
	// 16종 순차로 일어나는 동안 DB 커넥션을 점유하지 않기 위해서다. 실제 DB 작업은 importWriter의 별도 트랜잭션
	// 메서드(persist·recordFailedImport)로만 이뤄진다(PR #94 리뷰 권장사항 ②).
	@Scheduled(cron = "0 10 8 * * MON-FRI", zone = "Asia/Seoul")
	public void collect() {
		LocalDate tradingDate = businessDayCalendar.previousBusinessDay(LocalDate.now(clock));
		// 같은 거래일에 대해 정규 배치와 재시도(retryPendingInstruments)가 동시에 돌거나, 블루-그린 두 인스턴스가
		// 우연히 겹쳐도 실제로 KIS를 호출·저장하는 것은 하나뿐이어야 한다(COLLECT-STAB-001). 락을 얻지 못하면
		// "이번 실행 자체가 일어나지 않음"이므로 market_data_imports에 아무 행도 남기지 않고 조용히 반환한다.
		Optional<String> lockToken = stockCollectionLock.tryLock(tradingDate);
		if (lockToken.isEmpty()) {
			log.info("주식 분봉 수집 락을 얻지 못해 이번 실행을 건너뜁니다(다른 실행이 이미 처리 중) - tradingDate={}", tradingDate);
			return;
		}
		try {
			LocalDateTime collectedAt = LocalDateTime.now(clock);
			try {
				// 종목 목록 조회 자체도 이 try 안에 둔다 — 실패하면(진짜 전체 오류) 아래 catch가 잡아 FAILED로 남긴다.
				// 샌드박스 튜토리얼 종목은 조회 시점에 제외한다 — 검증·저장 어느 단계에도 등장하지 않는다(COLLECT-STAB-002).
				List<Instrument> stockInstruments = instrumentRepository
					.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK);
				List<InstrumentOutcome> outcomes = new ArrayList<>();
				for (Instrument instrument : stockInstruments) {
					outcomes.add(collectInstrument(instrument, tradingDate, collectedAt));
				}
				importWriter.persist(tradingDate, collectedAt, outcomes);
			} catch (RuntimeException ex) {
				// 종목별 KIS 호출 실패는 collectInstrument 내부에서 이미 그 종목 하나만의 실패로 흡수된다 — 여기까지 올라오는
				// 예외는 종목 하나로 좁힐 수 없는 진짜 전체 오류다(종목 목록 조회 실패, 검증/변환 로직 자체의 버그, 저장
				// 트랜잭션 실패 등). 이전에 계산해 둔 다른 종목의 결과(outcomes)가 있어도 여기서는 저장하지 않는다.
				log.error("KIS 과거 분봉 수집이 종목 단위로 좁힐 수 없는 오류로 중단되었습니다 (tradingDate={})", tradingDate, ex);
				importWriter.recordFailedImport(tradingDate, collectedAt,
					"수집이 예상치 못한 오류로 중단되었습니다: " + ex.getMessage());
			}
		} finally {
			stockCollectionLock.unlock(tradingDate, lockToken.get());
		}
	}

	// 정규 08:10 배치 이후 당일 재시도(market.stock.retry-cron, 기본 08:15~10:45 15분 간격 9회) — collect()를
	// 그대로 위임 호출한다(COLLECT-STAB-003). collect()가 이미 갖고 있는 종목 단위
	// existsByInstrumentIdAndTradingDate 스킵(기존 멱등성)을 그대로 재사용하므로, 재조회되는 것은 그 거래일에
	// 아직 분봉이 없는 종목뿐이다 — 새 판정 로직을 도입하지 않는다(spec.md 비즈니스 규칙, C-002 최소 구현).
	// 정규 배치와 재시도가 같은 tradingDate를 다루므로 collect() 내부의 StockCollectionLock으로 서로도 배제된다.
	@Scheduled(cron = "${market.stock.retry-cron}", zone = "Asia/Seoul")
	public void retryPendingInstruments() {
		collect();
	}

	private InstrumentOutcome collectInstrument(Instrument instrument, LocalDate tradingDate,
		LocalDateTime collectedAt) {
		boolean alreadyCollected = stockCandleRepository
			.existsByInstrumentIdAndTradingDate(instrument.getId(), tradingDate);
		if (alreadyCollected) {
			// UNIQUE(instrument_id, trading_date, candle_time) 기반 재실행 멱등성 — 이미 저장된 종목·거래일은 다시
			// 조회·저장하지 않는다. exists 여부만 확인해 이미 수집된 날의 최대 390행을 전부 로드하지 않는다(PR #94 리뷰
			// 권장사항 ①). 동일/상충 재수집 판정(SKIPPED_DUPLICATE 등, 이슈 #83)은 이번 범위가 아니다.
			return new InstrumentOutcome(instrument, List.of(), null);
		}

		List<RawMinuteCandleDto> rawCandles;
		try {
			rawCandles = kisHistoricalCandleClient.fetchMinuteCandles(instrument.getSymbol(), tradingDate);
		} catch (RuntimeException ex) {
			// KIS 호출 자체가 예외를 던지는 경우(타임아웃·연결 재설정 등 일시적 네트워크 실패 포함) 이 종목 하나만의
			// 실패로 흡수한다 — 나머지 종목은 계속 수집을 진행한다(PR #94 리뷰 권장사항 ③).
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

	// 종목 하나의 분봉 목록 전체를 검증한다 — 하나라도 위반하면 그 종목의 그날 분봉 전체를 원자적으로 저장하지 않는다.
	// 분봉 개수가 적거나 0건인 것 자체는 오류로 보지 않는다(실제 거래정지·거래 없음·빈 분 생략 가능성 — spec.md, 임계치는
	// Decision Gate이므로 이 클래스에서 임의 숫자를 도입하지 않는다).
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
