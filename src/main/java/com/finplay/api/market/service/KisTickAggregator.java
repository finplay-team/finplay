// KIS 실시간 체결 틱을 서버에서 1분 OHLCV로 집계한다 — 결과 모델(StockCandleDto)은 캔들 API·KrxReplayPriceProvider와 동일하다 (MKT-007, 이슈 #82)
package com.finplay.api.market.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// 인프라(WebSocket) 의존이 없는 순수 집계 로직이라 Spring 빈으로 등록하지 않는다 — KisRealtimePriceProvider·FakeKisRealtimePriceProvider가
// 각자 인스턴스를 직접 생성해 쓴다. 집계 결과의 영구 보관은 이번 범위 밖이라(plan.md 범위 제외) 인메모리에만 쌓는다.
public final class KisTickAggregator {

	private final Map<Long, MinuteBucket> openBuckets = new ConcurrentHashMap<>();
	private final Map<Long, CopyOnWriteArrayList<StockCandleDto>> closedCandlesByInstrument = new ConcurrentHashMap<>();

	// 체결 틱 하나를 반영한다. 직전 분봉과 같은 분이면 open/high/low/close/volume을 갱신하고, 새 분으로 넘어갔으면
	// 직전 분봉을 확정해 반환하고 새 분봉을 연다. 같은 종목의 틱은 항상 시간순으로 들어온다고 가정한다(WebSocket 체결 스트림 순서 보장).
	public synchronized Optional<StockCandleDto> onTick(
		Long instrumentId, LocalDateTime tickTime, BigDecimal price, long tickVolume) {
		LocalDateTime minuteStart = tickTime.truncatedTo(ChronoUnit.MINUTES);
		MinuteBucket bucket = openBuckets.get(instrumentId);

		if (bucket == null) {
			openBuckets.put(instrumentId, new MinuteBucket(minuteStart, price, tickVolume));
			return Optional.empty();
		}
		if (bucket.minuteStart.equals(minuteStart)) {
			bucket.accumulate(price, tickVolume);
			return Optional.empty();
		}

		StockCandleDto closedCandle = bucket.toCandle();
		closedCandlesByInstrument.computeIfAbsent(instrumentId, id -> new CopyOnWriteArrayList<>()).add(closedCandle);
		openBuckets.put(instrumentId, new MinuteBucket(minuteStart, price, tickVolume));
		return Optional.of(closedCandle);
	}

	// 확정된(마감된) 분봉만 반환한다 — 아직 열려 있는 현재 분봉은 포함하지 않는다(캔들 API의 "마감된 분봉만 노출" 계약과 동일).
	// from·to는 각각 선택이며 null이면 무제한으로 취급한다(StockPriceProvider 계약과 동일).
	public List<StockCandleDto> getClosedCandles(Long instrumentId, LocalDateTime from, LocalDateTime to) {
		List<StockCandleDto> candles = closedCandlesByInstrument.getOrDefault(instrumentId,
			new CopyOnWriteArrayList<>());
		LocalDateTime rangeFrom = from != null ? from : LocalDateTime.MIN;
		LocalDateTime rangeTo = to != null ? to : LocalDateTime.MAX;
		return candles
			.stream()
			.filter(candle -> {
				LocalDateTime candleDateTime = LocalDateTime.of(candle.tradingDate(), candle.candleTime());
				return !candleDateTime.isBefore(rangeFrom) && !candleDateTime.isAfter(rangeTo);
			})
			.toList();
	}

	private static final class MinuteBucket {

		private final LocalDateTime minuteStart;
		private final BigDecimal open;
		private BigDecimal high;
		private BigDecimal low;
		private BigDecimal close;
		private long volume;

		MinuteBucket(LocalDateTime minuteStart, BigDecimal price, long tickVolume) {
			this.minuteStart = minuteStart;
			this.open = price;
			this.high = price;
			this.low = price;
			this.close = price;
			this.volume = tickVolume;
		}

		void accumulate(BigDecimal price, long tickVolume) {
			if (price.compareTo(high) > 0) {
				high = price;
			}
			if (price.compareTo(low) < 0) {
				low = price;
			}
			close = price;
			volume += tickVolume;
		}

		StockCandleDto toCandle() {
			return new StockCandleDto(minuteStart.toLocalDate(), minuteStart.toLocalTime(), open, high, low, close,
				volume);
		}
	}
}
