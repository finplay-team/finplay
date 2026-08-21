// 공개된 1분봉 목록을 거래일·주·월 단위로 묶어 일봉·주봉·월봉 버킷을 만드는 순수 집계 클래스(이슈 #143) — Spring 빈이 아니다.
package com.finplay.api.domain.market.service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// StockReplayService가 공개 상한을 통과시켜 넘겨준 1분봉만 입력받는다 — 이 클래스는 공개 여부를 판단하지 않는다.
// 입력은 (tradingDate, candleTime) 오름차순이 보장된 목록이어야 하며, 그 순서를 그대로 이용해 버킷을 오름차순으로 만든다
// (별도 정렬을 하지 않는다 — 버킷 키가 tradingDate에 대해 단조 비감소이므로 LinkedHashMap 삽입 순서가 곧 오름차순이다).
// PR #151 리뷰 권장사항: 이 정렬 전제가 깨지면 open·close가 조용히 틀려진다. 호출부가 market.service 패키지 안의
// StockReplayService 하나뿐이므로(테스트도 같은 패키지) public을 열지 않고 package-private으로 좁혀 외부에서
// 정렬 안 된 입력으로 호출하는 경로 자체를 차단한다.
final class StockCandleAggregator {

	private StockCandleAggregator() {}

	// 1m은 이 클래스를 거치지 않는다(기존 경로 유지, ai/specs/013-candle-interval/plan.md). 1d·1w·1M만 받는다.
	static List<StockCandleDto> aggregate(List<StockCandleDto> minuteCandles, CandleInterval interval) {
		if (!interval.isAggregated()) {
			throw new IllegalArgumentException("StockCandleAggregator는 1m을 집계하지 않습니다: " + interval);
		}
		if (minuteCandles.isEmpty()) {
			return List.of();
		}

		Map<LocalDate, BucketAccumulator> buckets = new LinkedHashMap<>();
		for (StockCandleDto candle : minuteCandles) {
			LocalDate bucketStart = resolveBucketStart(candle.tradingDate(), interval);
			buckets.computeIfAbsent(bucketStart, key -> new BucketAccumulator()).accumulate(candle);
		}

		List<StockCandleDto> result = new ArrayList<>();
		for (Map.Entry<LocalDate, BucketAccumulator> entry : buckets.entrySet()) {
			result.add(entry.getValue().toDto(entry.getKey()));
		}
		return result;
	}

	// 버킷 경계는 spec의 확정 규칙을 그대로 옮긴 것이다 — 1d=거래일, 1w=그 주 월요일(ISO-8601), 1M=그 달 1일.
	// package-private(이슈 #155) — StockReplayService가 조회 하한을 좁히기 위해 같은 버킷 경계 규칙을 재사용한다.
	static LocalDate resolveBucketStart(LocalDate tradingDate, CandleInterval interval) {
		return switch (interval) {
			case ONE_DAY -> tradingDate;
			case ONE_WEEK -> tradingDate.with(DayOfWeek.MONDAY);
			case ONE_MONTH -> tradingDate.withDayOfMonth(1);
			case ONE_MINUTE -> throw new IllegalArgumentException("StockCandleAggregator는 1m을 집계하지 않습니다.");
		};
	}

	// 버킷 하나의 OHLCV를 누적한다 — open은 최초 분봉, close는 최종 분봉(입력이 오름차순이므로 매번 갱신하면 마지막 값이 남는다),
	// high·low는 최대·최소, volume은 합계.
	private static final class BucketAccumulator {
		private BigDecimal open;
		private BigDecimal high;
		private BigDecimal low;
		private BigDecimal close;
		private long volume;

		private void accumulate(StockCandleDto candle) {
			if (open == null) {
				open = candle.open();
				high = candle.high();
				low = candle.low();
			} else {
				high = high.max(candle.high());
				low = low.min(candle.low());
			}
			close = candle.close();
			volume += candle.volume();
		}

		private StockCandleDto toDto(LocalDate bucketStart) {
			return new StockCandleDto(bucketStart, LocalTime.MIDNIGHT, open, high, low, close, volume);
		}
	}
}
