// 재생세션(StockReplaySession)과 Clock을 조합해 주식 시장 개장 상태·현재가를 계산하는 읽기 전용 서비스
package com.finplay.api.market.service;

import com.finplay.api.market.domain.PreparationStatus;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StockReplayService {

	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);
	private static final LocalTime FIRST_CANDLE_END_TIME = LocalTime.of(9, 1);
	private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);

	// 집계 캔들(1d·1w·1M)의 from 생략 시 조회 하한과 결과 상한(spec 공통 계약 — 200개 캡, 이슈 #143)
	private static final int MAX_AGGREGATED_CANDLES = 200;
	private static final long LOOKBACK_FLOOR_DAYS = 400;
	private static final long LOOKBACK_FLOOR_WEEKS = 200;
	private static final long LOOKBACK_FLOOR_MONTHS = 200;

	private final StockReplaySessionRepository stockReplaySessionRepository;
	private final StockCandleRepository stockCandleRepository;
	private final Clock clock;
	private final BusinessDayCalendar businessDayCalendar;

	@Transactional(readOnly = true)
	public StockMarketStatus getMarketStatus() {
		LocalDateTime now = LocalDateTime.now(clock);
		boolean sessionReady = findReadySession(now.toLocalDate()).isPresent();
		return computeMarketStatus(sessionReady, now);
	}

	@Transactional(readOnly = true)
	public StockReplayPriceDto getCurrentPrice(Long instrumentId) {
		return getCurrentPrices(List.of(instrumentId)).get(0);
	}

	// 계좌(=market) 단위로 여러 종목을 평가할 때 종목과 무관한 전역 상태(현재시각·재생세션·시장상태)를 요청당 1회만 계산하고,
	// 종목별로 실제로 달라지는 분봉 조회(findRevealedCandle)만 반복한다 (PR #97 리뷰 권장사항 — HoldingValuationService 등이
	// 보유 종목 N개를 평가할 때 이 전역 상태를 종목 수만큼 중복 조회하던 낭비를 없앤다). 반환 순서는 instrumentIds와 일치한다.
	@Transactional(readOnly = true)
	public List<StockReplayPriceDto> getCurrentPrices(List<Long> instrumentIds) {
		LocalDateTime now = LocalDateTime.now(clock);
		Optional<StockReplaySession> readySession = findReadySession(now.toLocalDate());
		StockMarketStatus marketStatus = computeMarketStatus(readySession.isPresent(), now);

		if (readySession.isEmpty()) {
			return instrumentIds.stream()
				.map(instrumentId -> new StockReplayPriceDto(false, marketStatus, null, null, null))
				.toList();
		}

		LocalDate sourceTradingDate = readySession.get().getSourceTradingDate();
		LocalTime nowTime = now.toLocalTime();
		boolean isFirstCandleWindow = isWithinFirstCandleWindow(nowTime);
		return instrumentIds.stream().map(instrumentId -> {
			Optional<StockCandle> revealedCandle = findRevealedCandle(instrumentId, sourceTradingDate, nowTime);
			if (revealedCandle.isEmpty()) {
				return new StockReplayPriceDto(true, marketStatus, sourceTradingDate, null, null);
			}
			StockCandle candle = revealedCandle.get();
			var price = isFirstCandleWindow ? candle.getOpen() : candle.getClose();
			LocalDateTime sourceTime = LocalDateTime.of(sourceTradingDate, candle.getCandleTime());
			return new StockReplayPriceDto(true, marketStatus, sourceTradingDate, price, sourceTime);
		}).toList();
	}

	// 캔들 API — 아직 마감하지 않은 분봉은 절대 응답에 포함하지 않는다. 09:00~09:00:59(첫 분봉 구간)은 그 첫 분봉조차 아직
	// 마감 전이므로 빈 배열을 반환한다 — 가격 API(getCurrentPrice)가 같은 구간에서 첫 분봉의 시가를 예외적으로 노출하는 것과는
	// 다른 계약이다(리뷰 확정: PRD·spec.md의 "아직 마감하지 않은 분봉은 노출하지 않는다"를 캔들 목록에는 예외 없이 적용).
	// 재생세션이 준비되지 않았거나 공개된 분봉이 없으면 예외 없이 빈 목록을 반환한다(가격 API의 PRICE_UNAVAILABLE과 다른 계약).
	@Transactional(readOnly = true)
	public List<StockCandleDto> getRevealedCandles(Long instrumentId, LocalDateTime from, LocalDateTime to) {
		LocalDateTime now = LocalDateTime.now(clock);
		Optional<StockReplaySession> readySession = findReadySession(now.toLocalDate());
		if (readySession.isEmpty()) {
			return List.of();
		}

		LocalDate sourceTradingDate = readySession.get().getSourceTradingDate();
		Optional<LocalTime> cutoff = resolveRevealCutoff(now.toLocalTime());
		if (cutoff.isEmpty()) {
			return List.of();
		}

		LocalTime rangeStart = from != null ? from.toLocalTime() : LocalTime.MIN;
		LocalTime requestedEnd = to != null ? to.toLocalTime() : LocalTime.MAX;
		LocalTime rangeEnd = requestedEnd.isBefore(cutoff.get()) ? requestedEnd : cutoff.get();
		if (rangeStart.isAfter(rangeEnd)) {
			return List.of();
		}

		return stockCandleRepository
			.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
				instrumentId, sourceTradingDate, rangeStart, rangeEnd)
			.stream()
			.map(StockCandleDto::from)
			.toList();
	}

	// 집계 캔들(1d·1w·1M) API — 공개 상한(reveal bound)을 지키며 1분봉을 조회해 StockCandleAggregator로 묶는다(이슈 #143).
	// getRevealedCandles(1m 경로)는 이 메서드가 손대지 않는다. 재생세션이 없으면 어떤 interval이든 빈 목록이다
	// (spec.md "공개 상한" — 재생 준비 전 거래일이 일봉으로 미리 새어 나가는 것을 막는다).
	@Transactional(readOnly = true)
	public List<StockCandleDto> getRevealedAggregatedCandles(
		Long instrumentId, CandleInterval interval, LocalDate fromDate, LocalDate toDate) {
		LocalDateTime now = LocalDateTime.now(clock);
		Optional<StockReplaySession> readySession = findReadySession(now.toLocalDate());
		if (readySession.isEmpty()) {
			return List.of();
		}

		LocalDate sourceTradingDate = readySession.get().getSourceTradingDate();
		LocalDate requestedEnd = toDate != null ? toDate : sourceTradingDate;
		// 재생거래일을 절대 넘지 않는다(방어 규칙) — to가 재생거래일 이후를 가리켜도 그 이후 trading_date는 미공개다.
		LocalDate rangeEnd = requestedEnd.isBefore(sourceTradingDate) ? requestedEnd : sourceTradingDate;
		LocalDate rangeStart = fromDate != null ? fromDate : lookbackFloor(interval, rangeEnd);
		if (rangeStart.isAfter(rangeEnd)) {
			return List.of();
		}

		// 조회를 2회로 분리한다 — 미공개 분봉을 애초에 메모리에 올리지 않기 위해서다(한 번에 읽고 나중에 걸러내면
		// 필터를 빠뜨렸을 때 그대로 유출된다). (a) 과거 거래일(재생거래일 전부 공개)과 (b) 재생거래일 당일(컷오프까지만
		// 공개)을 별도 쿼리로 조회해 이어 붙인다 — 둘 다 tradingDate 오름차순이 유지되므로 (a) 다음 (b) 순서가 곧 전체
		// 오름차순이다.
		List<StockCandleDto> minuteCandles = new ArrayList<>();

		LocalDate sourceTradingDateMinusOne = sourceTradingDate.minusDays(1);
		LocalDate pastEnd = rangeEnd.isBefore(sourceTradingDateMinusOne) ? rangeEnd : sourceTradingDateMinusOne;
		if (!rangeStart.isAfter(pastEnd)) {
			minuteCandles.addAll(
				stockCandleRepository
					.findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
						instrumentId, rangeStart, pastEnd)
					.stream()
					.map(StockCandleDto::from)
					.toList());
		}

		boolean sourceTradingDateInRange = !rangeStart.isAfter(sourceTradingDate)
			&& !sourceTradingDate.isAfter(rangeEnd);
		if (sourceTradingDateInRange) {
			Optional<LocalTime> cutoff = resolveRevealCutoff(now.toLocalTime());
			if (cutoff.isPresent()) {
				minuteCandles.addAll(
					stockCandleRepository
						.findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
							instrumentId, sourceTradingDate, LocalTime.MIN, cutoff.get())
						.stream()
						.map(StockCandleDto::from)
						.toList());
			}
		}

		List<StockCandleDto> aggregated = StockCandleAggregator.aggregate(minuteCandles, interval);
		if (aggregated.size() <= MAX_AGGREGATED_CANDLES) {
			return aggregated;
		}
		return aggregated.subList(aggregated.size() - MAX_AGGREGATED_CANDLES, aggregated.size());
	}

	// getRevealedAggregatedCandles 전용 — from 생략 시 200개 버킷을 채우고도 남는 조회 하한(spec plan.md 확정값).
	private LocalDate lookbackFloor(CandleInterval interval, LocalDate rangeEnd) {
		return switch (interval) {
			case ONE_DAY -> rangeEnd.minusDays(LOOKBACK_FLOOR_DAYS);
			case ONE_WEEK -> rangeEnd.minusWeeks(LOOKBACK_FLOOR_WEEKS);
			case ONE_MONTH -> rangeEnd.minusMonths(LOOKBACK_FLOOR_MONTHS);
			case ONE_MINUTE -> throw new IllegalArgumentException("집계 캔들 전용 메서드입니다: " + interval);
		};
	}

	private Optional<StockReplaySession> findReadySession(LocalDate serviceDate) {
		return stockReplaySessionRepository
			.findByServiceDate(serviceDate)
			.filter(session -> session.getPreparationStatus() == PreparationStatus.READY);
	}

	private StockMarketStatus computeMarketStatus(boolean sessionReady, LocalDateTime now) {
		if (!sessionReady) {
			return StockMarketStatus.CLOSED;
		}
		LocalDate today = now.toLocalDate();
		LocalTime time = now.toLocalTime();
		boolean withinTradingHours = !time.isBefore(MARKET_OPEN_TIME) && time.isBefore(MARKET_CLOSE_TIME);
		return (businessDayCalendar.isBusinessDay(today) && withinTradingHours)
			? StockMarketStatus.OPEN
			: StockMarketStatus.CLOSED;
	}

	private boolean isWithinFirstCandleWindow(LocalTime time) {
		return !time.isBefore(MARKET_OPEN_TIME) && time.isBefore(FIRST_CANDLE_END_TIME);
	}

	// 가격 API(getCurrentPrice) 전용 — 첫 분봉 구간(09:00~09:00:59)에는 그 첫 분봉 자체(재조회 없이 asc 쿼리 결과를 그대로,
	// 쿼리 1회, 이슈 #16 확정 계약)를 반환해 상위 getCurrentPrice가 시가만 골라 노출한다. 09:01부터는 마감 완료된 마지막
	// 분봉을 반환한다. 캔들 목록 API(getRevealedCandles)는 첫 분봉 구간에서도 반드시 빈 배열이어야 하므로(아직 마감 전인
	// 분봉은 노출 금지) 이 메서드를 재사용하지 않고 별도의 resolveRevealCutoff를 쓴다 — 두 메서드의 컷오프 계약은 다르다.
	private Optional<StockCandle> findRevealedCandle(Long instrumentId, LocalDate sourceTradingDate, LocalTime now) {
		if (isWithinFirstCandleWindow(now)) {
			return stockCandleRepository.findFirstByInstrumentIdAndTradingDateOrderByCandleTimeAsc(
				instrumentId, sourceTradingDate);
		}
		LocalTime currentMinute = now.truncatedTo(ChronoUnit.MINUTES);
		if (currentMinute.isBefore(FIRST_CANDLE_END_TIME)) {
			// 09:00 이전(개장 전)이며 첫 분봉 구간도 아니므로 아직 공개된 분봉이 없다.
			return Optional.empty();
		}
		LocalTime cutoff = currentMinute.minusMinutes(1);
		return stockCandleRepository
			.findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
				instrumentId, sourceTradingDate, cutoff);
	}

	// 캔들 API(getRevealedCandles) 전용 컷오프 계산 — 09:01 이전(09:00~09:00:59 첫 분봉 구간과 개장 전 모두 포함)에는
	// 어떤 분봉도 마감되지 않았으므로 Optional.empty()(빈 배열)를 반환한다. 09:01부터는 마감이 완료된 마지막 분봉의
	// 시각(currentMinute - 1분)을 컷오프로 반환한다. 리뷰 확정(PR #87 차단 1): 첫 분봉이라도 마감 전에는 노출하지 않는다 —
	// findRevealedCandle과 달리 이 메서드는 DB를 조회하지 않고 시각 계산만으로 판정한다.
	private Optional<LocalTime> resolveRevealCutoff(LocalTime now) {
		LocalTime currentMinute = now.truncatedTo(ChronoUnit.MINUTES);
		if (currentMinute.isBefore(FIRST_CANDLE_END_TIME)) {
			return Optional.empty();
		}
		return Optional.of(currentMinute.minusMinutes(1));
	}
}
